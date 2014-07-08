package com.keepit.heimdal

import com.keepit.model.CustomBSONHandlers
import org.joda.time.DateTime

import scala.concurrent.duration.Duration

import reactivemongo.bson.{BSONValue, BSONDouble, BSONString, BSONDocument, BSONArray, BSONDateTime, BSONLong}
import reactivemongo.core.commands.{PipelineOperator, Match, GroupField, SumValue, Unwind, Sort, Descending, AddToSet}
import CustomBSONHandlers.BSONContextDataHandler

sealed trait ComparisonOperator {
  def toBSONMatchFragment: BSONValue
}

case class LessThan(value: ContextDoubleData) extends ComparisonOperator {
  def toBSONMatchFragment: BSONValue = BSONDocument("$lt" -> BSONDouble(value.value))
}

case class LessThanOrEqualTo(value: ContextDoubleData) extends ComparisonOperator {
  def toBSONMatchFragment: BSONValue = BSONDocument("$lte" -> BSONDouble(value.value))
}

case class GreaterThan(value: ContextDoubleData) extends ComparisonOperator {
  def toBSONMatchFragment: BSONValue = BSONDocument("$gt" -> BSONDouble(value.value))
}

case class GreaterThanOrEqualTo(value: ContextDoubleData) extends ComparisonOperator {
  def toBSONMatchFragment: BSONValue = BSONDocument("$gte" -> BSONDouble(value.value))
}

case class EqualTo(value: ContextData) extends ComparisonOperator {
  def toBSONMatchFragment: BSONValue = BSONContextDataHandler.write(value)
}

case class NotEqualTo(value: ContextData) extends ComparisonOperator {
  def toBSONMatchFragment: BSONValue = {
    val bsonValue : BSONValue = BSONContextDataHandler.write(value)
    BSONDocument("$ne" -> bsonValue)
  }
}

case class RegexMatch(value: String) extends ComparisonOperator {
  def toBSONMatchFragment: BSONValue = BSONDocument("$regex" -> value, "$options" -> "i")
}


sealed trait ContextRestriction {
  def toBSONMatchDocument: BSONDocument
}

case class AnyContextRestriction(field: String, operator: ComparisonOperator) extends ContextRestriction {
  def toBSONMatchDocument: BSONDocument = BSONDocument(field -> operator.toBSONMatchFragment)
}


case class AndContextRestriction(restrictions: ContextRestriction*) extends ContextRestriction {
  def toBSONMatchDocument: BSONDocument = BSONDocument("$and" -> BSONArray(restrictions.map(_.toBSONMatchDocument)))
}

case class OrContextRestriction(restrictions: ContextRestriction*) extends ContextRestriction {
  def toBSONMatchDocument: BSONDocument = BSONDocument("$or" -> BSONArray(restrictions.map(_.toBSONMatchDocument)))
}

case object NoContextRestriction extends ContextRestriction {
  def toBSONMatchDocument: BSONDocument = BSONDocument()
}

case class ConditionalContextRestriction(restriction: ContextRestriction, events: EventType*) extends ContextRestriction {
  def toBSONMatchDocument: BSONDocument = BSONDocument("$or" -> BSONArray(
    ComplementEventSet(events.toSet).toBSONMatchDocument,
    BSONDocument("$and" -> BSONArray(SpecificEventSet(events.toSet).toBSONMatchDocument, restriction.toBSONMatchDocument))
  ))
}

sealed trait EventSet {
  def toBSONMatchDocument: BSONDocument
}

case class SpecificEventSet(events: Set[EventType]) extends EventSet {
  def toBSONMatchDocument = BSONDocument(
    "eventType" -> BSONDocument(
      "$in" -> BSONArray(events.toSeq.map(eventType => BSONString(eventType.name)))
    )
  )
}

case class ComplementEventSet(events: Set[EventType]) extends EventSet {
  def toBSONMatchDocument = BSONDocument(
    "eventType" -> BSONDocument(
      "$nin" -> BSONArray(events.toSeq.map(eventType => BSONString(eventType.name)))
    )
  )
}

case object AllEvents extends EventSet {
  def toBSONMatchDocument = BSONDocument()
}


sealed trait EventGrouping {
    val fieldName: String
    def group(in: Stream[BSONDocument]): Seq[BSONDocument]
    val forceBreakdown : Boolean
    val allowBreakdown : Boolean
}

object EventGrouping {
  def apply(name: String) : EventGrouping = {
    if (name.startsWith("@")) {
      DerivedGrouping(name.tail)
    } else {
      NaturalGrouping(name)
    }
  }
}

case class NaturalGrouping(val fieldName: String) extends EventGrouping {
  def group(in: Stream[BSONDocument]): Seq[BSONDocument] = in.toSeq
  val forceBreakdown = false;
  val allowBreakdown = true;
}

sealed trait DerivedGrouping extends EventGrouping


//location (country), weekday, hour of day, local hour of day, OS, browser
object DerivedGrouping {
  def apply(name: String): DerivedGrouping = {
    null
  }
}


sealed trait MetricDefinition {
  def aggregationForTimeWindow(startTime: DateTime, timeWindowSize: Duration): (Seq[PipelineOperator], Stream[BSONDocument] => Seq[BSONDocument])
}

sealed trait SimpleMetricDefinition extends MetricDefinition {
  val fakeUsers: Seq[Long] = Seq[Long](6,32,60,90,93,125,127,128,217,249,259,262,265,279,286,342,346,395,396,421,422,424,428,429,435,436,475,478,494,495,545,550)
  def preFilter: PipelineOperator = Match(BSONDocument(
    "userId" -> BSONDocument(
      "$nin" -> BSONArray(fakeUsers.map(BSONLong(_)))
    )
  ))
}

class GroupedEventCountMetricDefinition(eventsToConsider: EventSet, contextRestriction: ContextRestriction, groupField: EventGrouping, breakDown: Boolean = false, keySort : Boolean = false) extends SimpleMetricDefinition {
  def aggregationForTimeWindow(startTime: DateTime, timeWindowSize: Duration): (Seq[PipelineOperator], Stream[BSONDocument] => Seq[BSONDocument]) = {
    val timeWindowSelector = Match(BSONDocument(
      "time" -> BSONDocument(
        "$gte" -> BSONDateTime(startTime.getMillis),
        "$lt"  -> BSONDateTime(startTime.getMillis + timeWindowSize.toMillis)
      )
    ))
    val eventSelector = Match(eventsToConsider.toBSONMatchDocument)
    val contextSelector = Match(contextRestriction.toBSONMatchDocument)

    val grouping = GroupField(groupField.fieldName)("count" -> SumValue(1))

    var pipeline: Seq[PipelineOperator] = Seq(timeWindowSelector, preFilter, eventSelector, contextSelector)

    if (breakDown || keySort ) pipeline = pipeline :+ Unwind(groupField.fieldName)

    pipeline =  pipeline :+ grouping

    if (keySort) pipeline = pipeline :+ Sort(Seq(Descending("_id")))
    else pipeline = pipeline :+ Sort(Seq(Descending("count")))

    val postprocess = (x: Stream[BSONDocument]) => x.toSeq

    (pipeline, postprocess)
  }
}

class SimpleEventCountMetricDefinition(eventsToConsider: EventSet, contextRestriction: ContextRestriction)
  extends GroupedEventCountMetricDefinition(eventsToConsider, contextRestriction, NaturalGrouping("_")) //This is bit of a hack to keep it dry. Could be done more efficiently with "find(...).count()". (-Stephen)



class GroupedUserCountMetricDefinition(eventsToConsider: EventSet, contextRestriction: ContextRestriction, groupField: EventGrouping, breakDown : Boolean = false, keySort : Boolean = false) extends SimpleMetricDefinition {
  def aggregationForTimeWindow(startTime: DateTime, timeWindowSize: Duration): (Seq[PipelineOperator], Stream[BSONDocument] => Seq[BSONDocument]) = {
    val timeWindowSelector = Match(BSONDocument(
      "time" -> BSONDocument(
        "$gte" -> BSONDateTime(startTime.getMillis),
        "$lt"  -> BSONDateTime(startTime.getMillis + timeWindowSize.toMillis)
      )
    ))
    val eventSelector = Match(eventsToConsider.toBSONMatchDocument)
    val contextSelector = Match(contextRestriction.toBSONMatchDocument)

    val grouping = GroupField(groupField.fieldName)("users" -> AddToSet("$userId"))

    var pipeline: Seq[PipelineOperator] = Seq(timeWindowSelector, preFilter, eventSelector, contextSelector)

    if (breakDown || keySort ) pipeline = pipeline :+ Unwind(groupField.fieldName)

    pipeline = pipeline ++ Seq(grouping, Unwind("users"), GroupField("_id")("count" -> SumValue(1), "users" -> AddToSet("$users")))

    if (keySort) pipeline = pipeline :+ Sort(Seq(Descending("_id")))
    else pipeline = pipeline :+ Sort(Seq(Descending("count")))

    val postprocess = (x: Stream[BSONDocument]) => x.toSeq

    (pipeline, postprocess)
  }
}

class GroupedUniqueFieldCountMetricDefinition(eventsToConsider: EventSet, contextRestriction: ContextRestriction, groupField: EventGrouping, fieldToCount: String, breakDown : Boolean = false) extends SimpleMetricDefinition {
  val sanitizedFieldName = fieldToCount.replace(".", "_")
  def aggregationForTimeWindow(startTime: DateTime, timeWindowSize: Duration): (Seq[PipelineOperator], Stream[BSONDocument] => Seq[BSONDocument]) = {
    val timeWindowSelector = Match(BSONDocument(
      "time" -> BSONDocument(
        "$gte" -> BSONDateTime(startTime.getMillis),
        "$lt"  -> BSONDateTime(startTime.getMillis + timeWindowSize.toMillis)
      )
    ))
    val eventSelector = Match(eventsToConsider.toBSONMatchDocument)
    val contextSelector = Match(contextRestriction.toBSONMatchDocument)

    val grouping = GroupField(groupField.fieldName)(sanitizedFieldName -> AddToSet("$" + fieldToCount))

    var pipeline: Seq[PipelineOperator] = Seq(timeWindowSelector, preFilter, eventSelector, contextSelector)

    if (breakDown) pipeline = pipeline :+ Unwind(groupField.fieldName)

    pipeline = pipeline ++ Seq(grouping, Unwind(sanitizedFieldName), GroupField("_id")("count" -> SumValue(1), sanitizedFieldName -> AddToSet("$" + fieldToCount)))

    pipeline = pipeline :+ Sort(Seq(Descending("count")))

    val postprocess = (x: Stream[BSONDocument]) => x.toSeq

    (pipeline, postprocess)
  }
}


