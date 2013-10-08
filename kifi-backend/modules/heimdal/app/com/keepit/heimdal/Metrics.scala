package com.keepit.heimdal

import org.joda.time.DateTime

import scala.concurrent.duration.Duration

import reactivemongo.bson.{BSONValue, BSONDouble, BSONString, BSONDocument, BSONArray, BSONDateTime}
import reactivemongo.core.commands.{PipelineOperator, Match, GroupField, SumValue, Unwind, Project, Sort, Descending, AddToSet}


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
  def toBSONMatchFragment: BSONValue = value match {
    case ContextDoubleData(x) => BSONDouble(x)
    case ContextStringData(s) => BSONString(s)
  }
}

case class NotEqualTo(value: ContextData) extends ComparisonOperator {
  def toBSONMatchFragment: BSONValue = {
    val bsonValue : BSONValue = value match {
      case ContextDoubleData(x) => BSONDouble(x)
      case ContextStringData(s) => BSONString(s)
    }
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

sealed trait EventSet

case class SpecificEventSet(events: Set[UserEventType]) extends EventSet
case object AllEvents extends EventSet


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

sealed trait SimpleMetricDefinition extends MetricDefinition

class GroupedEventCountMetricDefinition(eventsToConsider: EventSet, contextRestriction: ContextRestriction, groupField: EventGrouping, breakDown: Boolean = false, keySort : Boolean = false) extends SimpleMetricDefinition {
  def aggregationForTimeWindow(startTime: DateTime, timeWindowSize: Duration): (Seq[PipelineOperator], Stream[BSONDocument] => Seq[BSONDocument]) = {
    val timeWindowSelector = Match(BSONDocument(
      "time" -> BSONDocument(
        "$gte" -> BSONDateTime(startTime.getMillis),
        "$lt"  -> BSONDateTime(startTime.getMillis + timeWindowSize.toMillis)
      )
    ))
    val eventSelector = eventsToConsider match {
      case SpecificEventSet(events) =>
        Match(BSONDocument(
          "event_type" -> BSONDocument(
            "$in" -> BSONArray(events.toSeq.map(eventType => BSONString(eventType.name)))
          )
        ))
      case AllEvents =>Match(BSONDocument())
    }
    val contextSelector = Match(contextRestriction.toBSONMatchDocument)

    val grouping = GroupField(groupField.fieldName)("count" -> SumValue(1))

    var pipeline: Seq[PipelineOperator] = Seq(timeWindowSelector, eventSelector, contextSelector)

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
    val eventSelector = eventsToConsider match {
      case SpecificEventSet(events) =>
        Match(BSONDocument(
          "event_type" -> BSONDocument(
            "$in" -> BSONArray(events.toSeq.map(eventType => BSONString(eventType.name)))
          )
        ))
      case AllEvents =>Match(BSONDocument())
    }
    val contextSelector = Match(contextRestriction.toBSONMatchDocument)

    val grouping = GroupField(groupField.fieldName)("users" -> AddToSet("$user_id"))

    var pipeline: Seq[PipelineOperator] = Seq(timeWindowSelector, eventSelector, contextSelector)

    if (breakDown || keySort ) pipeline = pipeline :+ Unwind(groupField.fieldName)

    pipeline = pipeline ++ Seq(grouping, Unwind("users"), GroupField("_id")("count" -> SumValue(1), "users" -> AddToSet("$users")))

    if (keySort) pipeline = pipeline :+ Sort(Seq(Descending("_id")))
    else pipeline = pipeline :+ Sort(Seq(Descending("count")))

    val postprocess = (x: Stream[BSONDocument]) => x.toSeq
    
    (pipeline, postprocess)
  }
}


