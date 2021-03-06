package org.elastic4play.database

import java.nio.file.{Files, Paths}
import java.security.KeyStore

import scala.collection.JavaConverters._
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future, Promise}

import play.api.inject.ApplicationLifecycle
import play.api.libs.json.JsObject
import play.api.{Configuration, Logger}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import com.sksamuel.elastic4s.http._
import com.sksamuel.elastic4s.http.bulk.BulkResponseItem
import com.sksamuel.elastic4s.http.search.SearchHit
import com.sksamuel.elastic4s.searches._
import com.sksamuel.elastic4s.streams.ReactiveElastic.ReactiveElastic
import com.sksamuel.elastic4s.streams.{RequestBuilder, ResponseListener}
import javax.inject.{Inject, Named, Singleton}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import org.apache.http.auth.{AuthScope, UsernamePasswordCredentials}
import org.apache.http.client.CredentialsProvider
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.elasticsearch.client.RestClientBuilder.{HttpClientConfigCallback, RequestConfigCallback}

import org.elastic4play.{ConflictError, IndexNotFoundException, InternalError, SearchError}

/**
  * This class is a wrapper of ElasticSearch client from Elastic4s
  * It builds the client using configuration (ElasticSearch addresses, cluster and index name)
  * It add timed annotation in order to measure storage metrics
  */
@Singleton
class DBConfiguration @Inject()(
    config: Configuration,
    lifecycle: ApplicationLifecycle,
    @Named("databaseVersion") val version: Int,
    implicit val ec: ExecutionContext,
    implicit val actorSystem: ActorSystem
) {
  private[DBConfiguration] lazy val logger = Logger(getClass)

  def requestConfigCallback: RequestConfigCallback = (requestConfigBuilder: RequestConfig.Builder) ⇒ {
    requestConfigBuilder.setAuthenticationEnabled(credentialsProviderMaybe.isDefined)
    config.getOptional[Boolean]("search.circularRedirectsAllowed").foreach(requestConfigBuilder.setCircularRedirectsAllowed)
    config.getOptional[Int]("search.connectionRequestTimeout").foreach(requestConfigBuilder.setConnectionRequestTimeout)
    config.getOptional[Int]("search.connectTimeout").foreach(requestConfigBuilder.setConnectTimeout)
    config.getOptional[Boolean]("search.contentCompressionEnabled").foreach(requestConfigBuilder.setContentCompressionEnabled)
    config.getOptional[String]("search.cookieSpec").foreach(requestConfigBuilder.setCookieSpec)
    config.getOptional[Boolean]("search.expectContinueEnabled").foreach(requestConfigBuilder.setExpectContinueEnabled)
    //    config.getOptional[InetAddress]("search.localAddress").foreach(requestConfigBuilder.setLocalAddress)
    config.getOptional[Int]("search.maxRedirects").foreach(requestConfigBuilder.setMaxRedirects)
    //    config.getOptional[Boolean]("search.proxy").foreach(requestConfigBuilder.setProxy)
    config.getOptional[Seq[String]]("search.proxyPreferredAuthSchemes").foreach(v ⇒ requestConfigBuilder.setProxyPreferredAuthSchemes(v.asJava))
    config.getOptional[Boolean]("search.redirectsEnabled").foreach(requestConfigBuilder.setRedirectsEnabled)
    config.getOptional[Boolean]("search.relativeRedirectsAllowed").foreach(requestConfigBuilder.setRelativeRedirectsAllowed)
    config.getOptional[Int]("search.socketTimeout").foreach(requestConfigBuilder.setSocketTimeout)
    config.getOptional[Seq[String]]("search.targetPreferredAuthSchemes").foreach(v ⇒ requestConfigBuilder.setTargetPreferredAuthSchemes(v.asJava))
    requestConfigBuilder
  }

  lazy val credentialsProviderMaybe: Option[CredentialsProvider] =
    for {
      user     ← config.getOptional[String]("search.user")
      password ← config.getOptional[String]("search.password")
    } yield {
      val provider    = new BasicCredentialsProvider
      val credentials = new UsernamePasswordCredentials(user, password)
      provider.setCredentials(AuthScope.ANY, credentials)
      provider
    }

  lazy val sslContextMaybe: Option[SSLContext] = config.getOptional[String]("search.keyStore.path").map { keyStore ⇒
    val keyStorePath     = Paths.get(keyStore)
    val keyStoreType     = config.getOptional[String]("search.keyStore.type").getOrElse(KeyStore.getDefaultType)
    val keyStorePassword = config.getOptional[String]("search.keyStore.password").getOrElse("").toCharArray
    val keyInputStream   = Files.newInputStream(keyStorePath)
    val keyManagers = try {
      val keyStore = KeyStore.getInstance(keyStoreType)
      keyStore.load(keyInputStream, keyStorePassword)
      val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      kmf.init(keyStore, keyStorePassword)
      kmf.getKeyManagers
    } finally {
      keyInputStream.close()
    }

    val trustManagers = config
      .getOptional[String]("search.trustStore.path")
      .map { trustStorePath ⇒
        val keyStoreType       = config.getOptional[String]("search.trustStore.type").getOrElse(KeyStore.getDefaultType)
        val trustStorePassword = config.getOptional[String]("search.trustStore.password").getOrElse("").toCharArray
        val trustInputStream   = Files.newInputStream(Paths.get(trustStorePath))
        try {
          val keyStore = KeyStore.getInstance(keyStoreType)
          keyStore.load(trustInputStream, trustStorePassword)
          val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
          tmf.init(keyStore)
          tmf.getTrustManagers
        } finally {
          trustInputStream.close()
        }
      }
      .getOrElse(Array.empty)

    // Configure the SSL context to use TLS
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagers, trustManagers, null)
    sslContext
  }

  def httpClientConfig: HttpClientConfigCallback = (httpClientBuilder: HttpAsyncClientBuilder) ⇒ {
    sslContextMaybe.foreach(httpClientBuilder.setSSLContext)
    credentialsProviderMaybe.foreach(httpClientBuilder.setDefaultCredentialsProvider)
    httpClientBuilder
  }

  /**
    * Underlying ElasticSearch client
    */
  private[database] val client = ElasticClient(ElasticProperties(config.get[String]("search.uri")), requestConfigCallback, httpClientConfig)
  // when application close, close also ElasticSearch connection
  lifecycle.addStopHook { () ⇒
    Future {
      client.close()
    }
  }

  def execute[T, U](t: T)(
      implicit
      handler: Handler[T, U],
      manifest: Manifest[U]
  ): Future[U] = {
    logger.debug(s"Elasticsearch request: ${client.show(t)}")
    client.execute(t).flatMap {
      case RequestSuccess(_, _, _, r) ⇒ Future.successful(r)
      case RequestFailure(_, _, _, error) ⇒
        val exception = error.`type` match {
          case "index_not_found_exception"         ⇒ IndexNotFoundException
          case "version_conflict_engine_exception" ⇒ ConflictError(error.reason, JsObject.empty)
          case "search_phase_execution_exception"  ⇒ SearchError(error.reason)
          case _                                   ⇒ InternalError(s"Unknown error: $error")
        }
        exception match {
          case _: ConflictError ⇒
          case _                ⇒ logger.error(s"ElasticSearch request failure: ${client.show(t)}\n => $error")
        }
        Future.failed(exception)
    }
  }

  /**
    * Creates a Source (akka stream) from the result of the search
    */
  def source(searchRequest: SearchRequest): Source[SearchHit, NotUsed] = Source.fromPublisher(client.publisher(searchRequest))

  /**
    * Create a Sink (akka stream) that create entity in ElasticSearch
    */
  def sink[T](implicit builder: RequestBuilder[T]): Sink[T, Future[Unit]] = {
    val sinkListener = new ResponseListener[T] {
      override def onAck(resp: BulkResponseItem, original: T): Unit = ()

      override def onFailure(resp: BulkResponseItem, original: T): Unit =
        logger.warn(s"Document index failure ${resp.id}: ${resp.error.fold("unexpected")(_.toString)}\n$original")
    }
    val end = Promise[Unit]
    val complete = () ⇒ {
      if (!end.isCompleted)
        end.success(())
      ()
    }
    val failure = (t: Throwable) ⇒ {
      end.failure(t)
      ()
    }
    Sink
      .fromSubscriber(
        client.subscriber(
          batchSize = 100,
          concurrentRequests = 5,
          refreshAfterOp = false,
          listener = sinkListener,
          typedListener = ResponseListener.noop,
          completionFn = complete,
          errorFn = failure,
          flushInterval = None,
          flushAfter = None,
          failureWait = 2.seconds,
          maxAttempts = 10
        )
      )
      .mapMaterializedValue { _ ⇒
        end.future
      }
  }

  /**
    * Name of the index, suffixed by the current version
    */
  val indexName: String = config.get[String]("search.index") + "_" + version

  /**
    * return a new instance of DBConfiguration that points to the previous version of the index schema
    */
  def previousVersion: DBConfiguration =
    new DBConfiguration(config, lifecycle, version - 1, ec, actorSystem)
}
