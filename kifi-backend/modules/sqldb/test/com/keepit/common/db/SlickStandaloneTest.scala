package com.keepit.common.db

import com.keepit.common.time._
import com.keepit.common.db.slick.DBSession._
import com.keepit.test._
import org.specs2.mutable.Specification
import com.keepit.common.db.slick._
import org.joda.time.DateTime

class SlickStandaloneTest extends Specification with SqlDbTestInjector {

  "Slick" should {

    "using driver abstraction" in {

      case class Bar(
          id: Option[Id[Bar]] = None,
          name: String) extends Model[Bar] {
        def withId(id: Id[Bar]): Bar = this.copy(id = Some(id))
        def withUpdateTime(now: DateTime) = this
      }

      //could be easily mocked up
      trait BarRepo extends Repo[Bar] {
        //here you may have model specific queries...
        def getByName(name: String)(implicit session: RSession): Seq[Bar]
      }

      class BarRepoImpl(val db: DataBaseComponent, val clock: Clock) extends BarRepo with DbRepo[Bar] {
        import DBSession._
        import scala.slick.driver.H2Driver.simple._

        override def deleteCache(model: Bar)(implicit session: RSession): Unit = {}
        override def invalidateCache(model: Bar)(implicit session: RSession): Unit = {}

        type RepoImpl = BarTable
        class BarTable(tag: Tag) extends RepoTable[Bar](db, tag, "foo") {
          import scala.slick.driver.H2Driver.simple._
          def name = column[String]("name")
          def * = (id.?, name) <> (Bar.tupled, Bar.unapply _)
        }

        def table(tag: Tag) = new BarTable(tag)

        def getByName(name: String)(implicit session: RSession): Seq[Bar] = {
          val q = for (f <- rows if columnExtensionMethods(f.name) === valueToConstColumn(name)) yield (f)
          q.list
        }
      }

      withDb() { implicit injector =>
        val repo: BarRepoImpl = new BarRepoImpl(db.db, new SystemClock())
        2 === 2
        db.readWrite { implicit session =>
          val fooA = repo.save(Bar(name = "A"))
          fooA.id.get.id === 1
        }

        db.readWrite { implicit session =>
          val fooB = repo.save(Bar(name = "B"))
          fooB.id.get.id === 2
        }

        db.readWrite { implicit session =>
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
