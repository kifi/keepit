package com.keepit.rover.model

import com.keepit.common.db.VersionNumber
import com.keepit.rover.article.{ ArticleKind, Article }

object ArticleVersionProvider {
  def zero[A <: Article](implicit kind: ArticleKind[A]): ArticleVersion = ArticleVersion(kind.version, VersionNumber.ZERO)
  def next[A <: Article](previous: Option[ArticleVersion])(implicit kind: ArticleKind[A]): ArticleVersion = {
    previous match {
      case Some(version) if version.major > kind.version => throw new IllegalStateException(s"Version number of $kind must be increasing (current is ${kind.version}, got $previous)")
      case Some(version) if version.major == kind.version => version.copy(minor = version.minor + 1)
      case _ => zero[A]
    }
  }
}
