package com.keepit.model

import scala.slick.driver.JdbcDriver.Table
import scala.slick.lifted.{TableQuery, Tag}
import scala.slick.driver.JdbcProfile
import scala.slick.driver.JdbcDriver.simple._


case class UserA(id: Option[Int], first: String, last: String)

class UsersA(tag: Tag) extends Table[UserA](tag, "users") {
  def id = column[Option[Int]]("id", O.PrimaryKey, O.AutoInc)
  def first = column[String]("first")
  def last = column[String]("last")
  def * = (id, first, last) <> (UserA.tupled, UserA.unapply)
}


class TestRepo {
  val asdfasdf = TableQuery[UsersA]
}

case class Picture(url: String, id: Option[Int] = None)

trait PictureComponent {

  class Pictures(tag: Tag) extends Table[Picture](tag, "PICTURES") {
    def id = column[Int]("PIC_ID", O.PrimaryKey, O.AutoInc)
    def url = column[String]("PIC_URL", O.NotNull)

    def * = (url, id.?) <> (Picture.tupled, Picture.unapply)
  }

  val picturesQuery = TableQuery[Pictures]

  for {
    t <- picturesQuery if (t.id < 0)
  } yield t

  private val picturesAutoInc = picturesQuery returning picturesQuery.map(_.id) into { case (p, id) => p.copy(id = Option(id)) }
  //def insert(picture: Picture)(implicit session: Session): Picture = picturesAutoInc.insert(picture)
}
