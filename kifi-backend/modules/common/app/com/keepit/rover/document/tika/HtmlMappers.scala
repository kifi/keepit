package com.keepit.rover.document.tika

import org.apache.tika.parser.html.DefaultHtmlMapper

object HtmlMappers {
  val default = Some(new DefaultHtmlMapper {
    override def mapSafeElement(name: String) = {
      name.toLowerCase match {
        case "option" => "option"
        case _ => super.mapSafeElement(name)
      }
    }
  })
}
