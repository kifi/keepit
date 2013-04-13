package com.keepit.common.db

import com.keepit.common.time.Clock
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
import com.google.inject.{Stage, Guice, Module, Injector}
import scala.slick.session.{Database => SlickDatabase}
import com.google.inject.util.Modules

class SlickStandalonTest extends Specification with TestDBRunner {

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
        def getByName(name: String)(implicit session: RSession): Seq[Bar]
      }

      class BarRepoImpl(val db: DataBaseComponent, val clock: Clock) extends BarRepo with DbRepo[Bar] {
        import db.Driver.Implicit._ // here's the driver, abstracted away

        implicit object BarIdTypeMapper extends BaseTypeMapper[Id[Bar]] {
          def apply(profile: BasicProfile) = new IdMapperDelegate[Bar](profile)
        }

        override val table = new RepoTable[Bar](db, "foo") {
          def name = column[String]("name")
          def * = id.? ~ name <> (Bar, Bar.unapply _)
        }

        def getByName(name: String)(implicit session: RSession): Seq[Bar] = {
          val q = for ( f <- table if f.name is name ) yield (f)
          q.list
        }
      }

      withDB() { implicit injector =>
        val repo: BarRepoImpl = new BarRepoImpl(db.db, new Clock())
        2 == 2
        db.readWrite{ implicit session =>
          val fooA = repo.save(Bar(name = "A"))
          fooA.id.get.id === 1
        }

        db.readWrite{ implicit session =>
          val fooB = repo.save(Bar(name = "B"))
          fooB.id.get.id === 2
        }

        db.readWrite{ implicit session =>
          repo.all().size === 2
          repo.count(session) === 2
          val a = repo.getByName("A")
          a.size === 1
          a.head.name === "A"
        }

      }
      1 === 1
    }
  }
}
