package com.keepit.common.analytics

trait MongoFunc {
  val js: String
}

case class MongoKeyMapFunc(js: String) extends MongoFunc
object MongoKeyMapFunc {
  val DATE_BY_HOUR = MongoKeyMapFunc("""
    function(doc) {
      var date = new Date(doc.createdAt);
      var dateKey = date.getFullYear() + '-' + (date.getMonth()+1) + '-' + date.getDate() + '';
      var dateKey = date.getFullYear() + '-' + (date.getMonth()+1) + '-' + date.getDate() + ' '+date.getHours()+':00';
      return {'day':dateKey};
    }
    """)
  val DATE = MongoKeyMapFunc("""
    function(doc) {
      var date = new Date(doc.createdAt);
      var dateKey = date.getFullYear() + '-' + (date.getMonth()+1) + '-' + date.getDate() + '';
      return {'day':dateKey};
    }
    """)
  val USER_DATE = MongoKeyMapFunc("""
    function(doc) {
      var date = new Date(doc.createdAt);
      var dateKey = date.getFullYear() + '-' + (date.getMonth()+1) + '-' + date.getDate() + '';
      var userId = doc.metaData.userId;
      return {'user':userId, 'date':dateKey};
    }
    """)
  val USER_DATE_HOUR  = MongoKeyMapFunc("""
    function(doc) {
      var date = new Date(doc.createdAt);
      var dateKey = date.getFullYear() + '-' + (date.getMonth()+1) + '-' + date.getDate() + ' ' + date.getHours()+":00";
      var userId = doc.metaData.userId;
      return {'user':userId, 'date':dateKey};
    }
    """)
}

case class MongoMapFunc(js: String) extends MongoFunc
object MongoMapFunc {
  val USER_DATE_COUNT = MongoMapFunc("""
    function() {
      var date = new Date(this.createdAt);
      var dateKey = date.getFullYear() + '-' + (date.getMonth()+1) + '-' + date.getDate() + '';
      emit({day: dateKey,userId: this.metaData.userId},{count:1});
    };
    """)

  val USER_WEEK_COUNT = MongoMapFunc("""
    function() {
      var d = new Date(this.createdAt);
      var date = new Date(d.getTime() + 24*60*60*1000*(7 - d.getDay()));
      var weekKey = date.getFullYear() + '-' + (date.getMonth()+1) + '-' + date.getDate() + '';
      emit({day: weekKey,userId: this.metaData.userId},{count:1});
    };
    """)

  val USER_MONTH_COUNT = MongoMapFunc("""
    function() {
      var date = new Date(this.createdAt);
      date.setDate(1);
      date.setMonth(date.getMonth()+1);
      var monthKey = date.getFullYear() + '-' + (date.getMonth()+1) + '-' + date.getDate() + '';
      emit({day: monthKey,userId: this.metaData.userId},{count:1});
    };
    """)

  val KEY_DAY_COUNT = MongoMapFunc("""
    function() {
      emit(this['_id']['day'], {count: 1});
    }
    """)
  val DATE_COUNT = MongoMapFunc("""
    function() {
      var date = new Date(this.createdAt);
      var dateKey = date.getFullYear() + '-' + (date.getMonth()+1) + '-' + date.getDate() + '';
      emit(dateKey,{count:1});
    }
    """)
}

case class MongoReduceFunc(js: String) extends MongoFunc
object MongoReduceFunc {
  val KEY_AGGREGATE = MongoReduceFunc("""function(obj, prev) {prev.count++;}""")
  val BASIC_COUNT = MongoReduceFunc("""
    function(key, values) {
      var count = 0;
      values.forEach(function(v) {
        count += v['count'];
      });
      return {count: count};
    }
    """)
}
