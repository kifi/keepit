package com.keepit.common.db

import scala.slick.jdbc.StaticQuery.interpolation

import com.keepit.common.time.Clock
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.test._
import com.keepit.inject._
import com.keepit.macros.Location
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
import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException
import org.h2.jdbc.JdbcSQLException

class SlickTest extends Specification with SqlDbTestInjector {

  "Slick" should {

    "using driver abstraction" in {
      withDb() { implicit injector =>

        case class Bar(
            id: Option[Id[Bar]] = None,
            name: String) extends Model[Bar] {
          def withId(id: Id[Bar]): Bar = this.copy(id = Some(id))
          def withUpdateTime(now: DateTime) = this
        }

        //could be easily mocked up
        trait BarRepo extends Repo[Bar] {
          //here you may have model specific queries...
          def getByName(name: String)(implicit session: ROSession): Seq[Bar]
          def getByNameSqlInterpulation(name: String)(implicit session: ROSession): Seq[String]
          def getByNameSqlInterpulationSqlInjection(name: String)(implicit session: ROSession): Seq[String]
          def getCurrentSeqNum()(implicit session: RSession): SequenceNumber[Bar]
        }

        //we can abstract out much of the standard repo and have it injected/mocked out
        class BarRepoImpl(val db: DataBaseComponent, val clock: Clock) extends BarRepo with DbRepo[Bar] {
          import DBSession._
          import scala.slick.driver.H2Driver.simple._

          private val sequence = db.getSequence[Bar]("bar")

          override def save(bar: Bar)(implicit session: RWSession): Bar = { sequence.incrementAndGet(); super.save(bar) }
          def getCurrentSeqNum()(implicit session: RSession) = { sequence.getLastGeneratedSeq() }

          override def deleteCache(model: Bar)(implicit session: RSession): Unit = {}
          override def invalidateCache(model: Bar)(implicit session: RSession): Unit = {}

          type RepoImpl = BarTable
          class BarTable(tag: Tag) extends RepoTable[Bar](db, tag, "foo") {
            import scala.slick.driver.H2Driver.simple._
            def name = column[String]("name")
            def * = (id.?, name) <> (Bar.tupled, Bar.unapply _)
          }

          def table(tag: Tag) = new BarTable(tag)

          def getByName(name: String)(implicit session: ROSession): Seq[Bar] = {
            val q = for (f <- rows if columnExtensionMethods(f.name) === valueToConstColumn(name)) yield (f)
            q.list
          }

          def getByNameSqlInterpulation(name: String)(implicit session: ROSession): Seq[String] = {
            val q = sql"select name from foo where name=$name".as[String]
            q.list
          }

          def getByNameSqlInterpulationSqlInjection(name: String)(implicit session: ROSession): Seq[String] = {
            val q = sql"select name from foo where name='#$name'".as[String]
            // println(q.getStatement) // can be removed?
            q.list
          }
        }

        val repo: BarRepo = new BarRepoImpl(inject[DataBaseComponent], inject[Clock])

        //just for testing you know...
        inject[Database].readWrite { implicit session =>
          val fooA = repo.save(Bar(name = "A"))
          fooA.id.get.id === 1
          repo.getCurrentSeqNum().value === 1
          val fooB = repo.save(Bar(name = "B"))
          fooB.id.get.id === 2
          repo.getCurrentSeqNum().value === 2
        }

        inject[Database].readOnlyMaster { implicit session =>
          repo.count(session) === 2
          val a = repo.getByName("A")
          a.size === 1
          a.head.name === "A"
        }

        inject[Database].readOnlyMaster { implicit session =>
          val a = repo.getByNameSqlInterpulation("A")
          a.size === 1
          a.head === "A"
        }

        inject[Database].readOnlyMaster { implicit session =>
          repo.count(session) === 2
        }

        inject[Database].readOnlyReplica { implicit session =>
          repo.count(session) === 2
        }

        inject[Database].readOnlyReplica { implicit session =>
          repo.count(session) === 2
        }(Location.capture)

        inject[Database].readOnlyMaster { implicit session =>
          repo.getByNameSqlInterpulationSqlInjection("A';drop table foo;select * from foo where name ='") must throwA[JdbcSQLException]
        }

      }
    }

    "rollback transaction" in {

      withDb() { implicit injector =>
        val db = inject[Database]
        import DBSession._
        import scala.slick.driver.H2Driver.simple._

        val table = (tag: Tag) => new Table[Int](tag, "t") {
          def a = column[Int]("a")
          def * = a
        }

        val rows = TableQuery(table)

        rows.ddl

        db.readWrite { implicit session =>
          rows.ddl.create
        }

        val q = rows.map(s => s)

        db.readWrite { implicit session =>
          rows.insert(42)
          q.firstOption === Some(42)
          session.rollback()
        }

        db.readOnlyMaster { implicit session =>
          q.firstOption === None
        }

        db.readWrite { implicit session =>
          rows.insert(1)
        }

        db.readWrite { implicit session =>
          rows.map(s => s).delete
          q.firstOption === None
          session.rollback()
        }

        db.readOnlyMaster { implicit session =>
          q.firstOption === Some(1)
        }

        try {
          db.readWrite { implicit session =>
            rows.map(s => s).delete
            q.firstOption === None
            throw new Exception
          }
        } catch {
          case _: Throwable => //ignore
        }

        db.readOnlyMaster { implicit session =>
          q.firstOption === Some(1)
        }

        db.readWrite { implicit session =>
          rows.map(s => s).delete
        }

        db.readOnlyMaster { implicit session =>
          q.firstOption === None
        }
      }
    }

    "using external id" in {
      withDb() { implicit injector =>

        case class Bar(
            id: Option[Id[Bar]] = None,
            externalId: ExternalId[Bar] = ExternalId(),
            name: String) extends ModelWithExternalId[Bar] {
          def withId(id: Id[Bar]): Bar = this.copy(id = Some(id))
          def withUpdateTime(now: DateTime) = this
        }

        //could be easily mocked up
        trait BarRepo extends Repo[Bar] with ExternalIdColumnFunction[Bar] {
          //here you may have model specific queries...
          def getByName(name: String)(implicit session: ROSession): Seq[Bar]
        }

        //we can abstract out much of the standard repo and have it injected/mocked out
        class BarRepoImpl(val db: DataBaseComponent, val clock: Clock) extends BarRepo with DbRepo[Bar] with ExternalIdColumnDbFunction[Bar] {
          import DBSession._
          import scala.slick.driver.H2Driver.simple._

          override def deleteCache(model: Bar)(implicit session: RSession): Unit = {}
          override def invalidateCache(model: Bar)(implicit session: RSession): Unit = {}

          type RepoImpl = BarTable
          class BarTable(tag: Tag) extends RepoTable[Bar](db, tag, "foo") with ExternalIdColumn[Bar] {
            import scala.slick.driver.H2Driver.simple._
            def name = column[String]("name")
            def * = (id.?, externalId, name) <> (Bar.tupled, Bar.unapply _)
          }

          def table(tag: Tag) = new BarTable(tag)

          def getByName(name: String)(implicit session: ROSession): Seq[Bar] = {
            val q = for (f <- rows if columnExtensionMethods(f.name) === valueToConstColumn(name)) yield (f)
            q.list
          }
        }

        val repo: BarRepo = new BarRepoImpl(inject[DataBaseComponent], inject[Clock])

        //just for testing you know...
        val (b1, b2) = inject[Database].readWrite { implicit session =>
          (repo.save(Bar(name = "A")), repo.save(Bar(name = "B")))
        }

        inject[Database].readOnlyMaster { implicit session =>
          repo.count(session) === 2
          repo.get(b1.externalId) === b1
          repo.get(b2.externalId) === b2
        }
      }
    }

  }
}
