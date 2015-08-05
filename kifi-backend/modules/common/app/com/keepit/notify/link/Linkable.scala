package com.keepit.notify.link

trait Linkable[A] extends (A => String) { self =>

 override def apply(v1: A): String = getLink(v1)

  def getLink(value: A): String

  def map(f: String => String): Linkable[A] = new Linkable[A] {

    override def getLink(value: A): String = f(self.getLink(value))

  }

  def contramap[B](f: B => A): Linkable[B] = new Linkable[B] {

    override def getLink(value: B): String = self.getLink(f(value))

  }

}

object Linkable {

  def kifiLink(path: String): String = s"https://kifi.com/$path"

  def apply[A](implicit linkable: Linkable[A]) = linkable

  implicit object StringIsLinkable extends Linkable[String] {

    override def getLink(value: String): String = value

  }

}
