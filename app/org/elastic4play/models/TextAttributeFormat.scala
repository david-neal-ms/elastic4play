package org.elastic4play.models

import play.api.libs.json.{ JsString, JsValue }

import com.sksamuel.elastic4s.ElasticDsl.field
import com.sksamuel.elastic4s.mappings.FieldType.StringType
import com.sksamuel.elastic4s.mappings.StringFieldDefinition
import org.scalactic._

import org.elastic4play.controllers.{ InputValue, JsonInputValue, StringInputValue }
import org.elastic4play.{ AttributeError, InvalidFormatAttributeError }

object TextAttributeFormat extends AttributeFormat[String]("text") {
  override def checkJson(subNames: Seq[String], value: JsValue): Or[JsValue, One[InvalidFormatAttributeError]] = value match {
    case _: JsString if subNames.isEmpty ⇒ Good(value)
    case _                               ⇒ formatError(JsonInputValue(value))
  }

  override def fromInputValue(subNames: Seq[String], value: InputValue): String Or Every[AttributeError] = {
    if (subNames.nonEmpty)
      formatError(value)
    else
      value match {
        case StringInputValue(Seq(v))    ⇒ Good(v)
        case JsonInputValue(JsString(v)) ⇒ Good(v)
        case _                           ⇒ formatError(value)
      }
  }

  override def elasticType(attributeName: String): StringFieldDefinition = field(attributeName, StringType)
}
