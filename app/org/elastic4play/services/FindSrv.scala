package org.elastic4play.services

import scala.concurrent.{ExecutionContext, Future}

import play.api.libs.json.JsObject
import play.api.libs.json.JsValue.jsValueToJsLookup

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.searches.queries.Query
import javax.inject.{Inject, Singleton}

import org.elastic4play.database.{DBConfiguration, DBFind}
import org.elastic4play.models.{AbstractModelDef, BaseEntity, BaseModelDef}
import org.elastic4play.services.QueryDSL._

case class QueryDef(query: Query)

@Singleton
class FindSrv @Inject()(dbfind: DBFind, modelSrv: ModelSrv, implicit val ec: ExecutionContext) {

  def switchTo(db: DBConfiguration) = new FindSrv(dbfind.switchTo(db), modelSrv, ec)

  def apply(
      modelName: Option[String],
      queryDef: QueryDef,
      range: Option[String],
      sortBy: Seq[String]
  ): (Source[BaseEntity, NotUsed], Future[Long]) = {
    val query        = modelName.fold(queryDef)(m ⇒ and("relations" ~= m, queryDef)).query
    val (src, total) = dbfind(range, sortBy)(indexName ⇒ search(indexName).query(query))
    val entities = src.map { attrs ⇒
      modelName match {
        //case Some("audit") ⇒ auditModel.get()(attrs)
        case Some(m) ⇒ modelSrv(m).getOrElse(sys.error("TODO"))(attrs)
        case None ⇒
          val tpe   = (attrs \ "_type").asOpt[String].getOrElse(sys.error("TODO"))
          val model = modelSrv(tpe).getOrElse(sys.error("TODO"))
          model(attrs)
      }
    }
    (entities, total)
  }

  def apply(model: BaseModelDef, queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[BaseEntity, NotUsed], Future[Long]) = {
    val (src, total) = dbfind(range, sortBy)(indexName ⇒ search(indexName).query(and("relations" ~= model.modelName, queryDef).query))
    val entities     = src.map(attrs ⇒ model(attrs))
    (entities, total)
  }

  def apply[M <: AbstractModelDef[M, E], E <: BaseEntity](
      model: M,
      queryDef: QueryDef,
      range: Option[String],
      sortBy: Seq[String]
  ): (Source[E, NotUsed], Future[Long]) = {
    val (src, total) = dbfind(range, sortBy)(indexName ⇒ search(indexName).query(and("relations" ~= model.modelName, queryDef).query))
    val entities     = src.map(attrs ⇒ model(attrs))
    (entities, total)
  }

  def apply(model: BaseModelDef, queryDef: QueryDef, aggs: Agg*): Future[JsObject] =
    dbfind(
      indexName ⇒ search(indexName).query(and("relations" ~= model.modelName, queryDef).query).aggregations(aggs.flatMap(_.apply(model))).size(0)
    ).map { searchResponse ⇒
      aggs
        .map(_.processResult(model, searchResponse.aggregations))
        .reduceOption(_ ++ _)
        .getOrElse(JsObject.empty)
    }
}
