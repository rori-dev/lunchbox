package util.feed

import java.net._

import java.time.OffsetDateTime
import play.api.http.MimeTypes

case class AtomFeed(
    id: URI,
    title: String,
    selfLink: URL,
    updated: OffsetDateTime,
    entries: Seq[AtomFeedEntry])

case class AtomFeedEntry(
    id: URI,
    title: String,
    author: Author,
    content: Content,
    published: OffsetDateTime,
    updated: OffsetDateTime)

case class Content(
    contentType: String,
    body: String)

object Content {
  def apply(content: play.twirl.api.Content): Content = {
    val contentType = content.contentType match {
      case MimeTypes.HTML => "html"
      case MimeTypes.TEXT => "text"
      case mime => mime
    }
    apply(contentType, content.body)
  }
}

case class Author(name: String)
