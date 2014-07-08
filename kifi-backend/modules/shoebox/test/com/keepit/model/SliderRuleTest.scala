package com.keepit.model

import org.joda.time.DateTime
import org.specs2.mutable._

import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.test._

import play.api.libs.json._

class SliderRuleTest extends Specification with ShoeboxTestInjector {
  "SliderRule" should {
    "save, load by group name, cache group versions" in {
      withDb() { implicit injector =>
        val repo = inject[SliderRuleRepo]
        inject[SliderRuleRepo] must be(repo) // singleton

        val (r1, r2, r3, r4, foo, bar) = inject[Database].readWrite{ implicit session =>
          (repo.save(SliderRule(None, "foo", "rule1", None)),
           repo.save(SliderRule(None, "foo", "rule2", Some(JsArray(Seq(JsNumber(8)))))),
           repo.save(SliderRule(None, "bar", "rule1", None)),
           repo.save(SliderRule(None, "bar", "rule2", Some(JsArray(Seq(JsNumber(9)))))),
           repo.getGroup("foo"),
           repo.getGroup("bar"))
        }

        foo.rules.map(_.id) === Seq(r1.id, r2.id)
        bar.rules.map(_.id) === Seq(r3.id, r4.id)

        inject[Database].readOnlyMaster{ implicit session =>
          repo.getGroup("foo") must be(foo)  // in-memory cache should work
          repo.getGroup("bar") must be(bar)
        }

        inject[Database].readWrite{ implicit session =>
          repo.save(foo.rules(1).withParameters(None))
          repo.getGroup("foo").version must be_>(foo.version)
          repo.getGroup("bar") must be(bar)  // still in memory cache
        }
      }
    }

    "compute group version based on last update time" in {
      val t1 = new DateTime(2008, 12, 15, 16, 42, 38, 180, DEFAULT_DATE_TIME_ZONE)
      val (t2, t3) = (t1.plus(2), t1.plus(4))
      SliderRuleGroup(Seq(
        SliderRule(None, "foo", "a", None, updatedAt = t2),
        SliderRule(None, "foo", "b", None, updatedAt = t3),
        SliderRule(None, "foo", "c", None, updatedAt = t1)))
      .version === "forctjoo"
    }

    "format compact JSON representation" in {
      val t = new DateTime(2008, 12, 15, 16, 42, 38, 184, DEFAULT_DATE_TIME_ZONE)
      SliderRuleGroup(Seq(
        SliderRule(None, "foo", "a", None, updatedAt = t),
        SliderRule(None, "foo", "b", Some(JsArray(Seq(JsBoolean(true), JsNumber(9)))), updatedAt = t)))
      .compactJson.toString === """{"version":"forctjoo","rules":{"a":1,"b":[true,9]}}"""
    }
  }
}
