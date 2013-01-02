package com.keepit.common.db

//import scala.slick.session.Database
//import scala.slick.session.Session
import org.scalaquery.session.Database
import org.scalaquery.session.Session
import java.sql.PreparedStatement

case class ReadOnlySession(session: Session)
case class ReadWriteSession(session: Session)

object Session {
  implicit def readOnlySession2Session(readOnly: ReadOnlySession): Session = readOnly.session
  implicit def readWriteSession2Session(readWrite: ReadWriteSession): Session = readWrite.session
}