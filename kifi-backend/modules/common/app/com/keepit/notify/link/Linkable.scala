package com.keepit.notify.link

import scala.annotation.implicitNotFound

@implicitNotFound("No Linkable instance found for ${A}")
trait Linkable[A] extends (A => String) { self =>

  override def apply(v1: A): String = link(v1)

  def link(value: A): String

  def map(f: String => String): Linkable[A] = new Linkable[A] {

    override def link(value: A): String = f(self.link(value))

  }

  def contramap[B](f: B => A): Linkable[B] = new Linkable[B] {

    override def link(value: B): String = self.link(f(value))

  }

}

object Linkable {

  def kifiLink(path: String): String = s"https://www.kifi.com/$path"

  def apply[A](implicit linkable: Linkable[A]) = linkable

  def apply[A](fn: A => String) = new Linkable[A] {

    override def link(value: A): String = fn(value)

  }

  implicit object StringIsLinkable extends Linkable[String] {

    override def link(value: String): String = value

  }

  def fromExisting[A, B](f: B => A)(implicit existing: Linkable[A]) = existing.contramap(f)

  implicit class LinkableOps[A](value: A)(implicit linkable: Linkable[A]) {

    def link = linkable.link(value)

  }

}
