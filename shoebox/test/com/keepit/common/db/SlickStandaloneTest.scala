package com.keepit.common.db

import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.test._
import scala.slick.lifted._
import scala.slick.driver.H2Driver.Implicit._
import scala.slick.driver._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import org.specs2.mutable.Specification
import scala.slick.lifted.Query
import com.keepit.common.db.slick._
import org.joda.time.DateTime

class SlickStandaloneTest extends Specification with TestDBRunner {

  "Slick" should {

    "using driver abstraction" in {

      case class Bar(
        id: Option[Id[Bar]] = None,
        name: String
      ) extends Model[Bar] {
        def withId(id: Id[Bar]): Bar = this.copy(id = Some(id))
        def withUpdateTime(now: DateTime) = this
      }

      //could be easily mocked up
      trait BarRepo extends Repo[Bar] {
        //here you may have model specific queries...
        def getByName(name: String)(implicit session: ROSession): Seq[Bar]
      }

      class BarRepoImpl(val db: DataBaseComponent) extends BarRepo with DbRepo[Bar] {
        import db.Driver.Implicit._ // here's the driver, abstracted away

        implicit object BarIdTypeMapper extends BaseTypeMapper[Id[Bar]] {
          def apply(profile: BasicProfile) = new IdMapperDelegate[Bar](profile)
        }

        override lazy val table = new RepoTable[Bar](db, "foo") {
          def name = column[String]("name")
          def * = id.? ~ name <> (Bar, Bar.unapply _)
        }

        def getByName(name: String)(implicit session: ROSession): Seq[Bar] = {
          val q = for ( f <- table if f.name is name ) yield (f)
          q.list
        }

        //only for testing
        def createTableForTesting()(implicit session: RWSession) = table.ddl.create
      }

      withDB { db =>
        val repo: BarRepo = new BarRepoImpl(db.db)

        //just for testing you know...
        db.readWrite{ implicit session =>
          repo.asInstanceOf[BarRepoImpl].createTableForTesting() //only in test mode we should know about the implementation
          val fooA = repo.save(Bar(name = "A"))
          fooA.id.get.id === 1
          val fooB = repo.save(Bar(name = "B"))
          fooB.id.get.id === 2
        }

        db.readOnly{ implicit session =>
          repo.count(session) === 2
          val a = repo.getByName("A")
          a.size === 1
          a.head.name === "A"
        }
      }
    }
  }
}
