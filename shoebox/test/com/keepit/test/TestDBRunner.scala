package com.keepit.test

import com.google.inject.Injector
import com.keepit.common.db.slick.Database
import com.keepit.inject.RichInjector
import com.keepit.common.db.slick.DbRepo
import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.common.db.slick.H2
import com.keepit.common.db.slick.TableInitListener
import scala.slick.lifted.DDL
import com.keepit.common.db.slick.TableWithDDL

trait TestDBRunner {
  def withDB[T](f: Database => T)(implicit injector: RichInjector) = {
    val db = injector.inject[Database]
    val h2 = injector.inject[DataBaseComponent].asInstanceOf[H2]
    h2.initListener = Some(new TableInitListener {
      def init(table: TableWithDDL) = initTable(db, table)
    })
    try {
      (f(db))
    } finally {
      db.readWrite { implicit session =>
        val conn = session.conn
        val st = conn.createStatement()
        st.execute("DROP ALL OBJECTS")
      }
    }
  }

  def initTable(db: Database, table: TableWithDDL): Unit = {
    println(s"initiating table [$table.tableName]")
    db.readWrite { implicit session =>
      session.withTransaction {
        for (s <- table.ddl.createStatements)
          session.withPreparedStatement(s)(_.execute)
      }
    }
  }
}