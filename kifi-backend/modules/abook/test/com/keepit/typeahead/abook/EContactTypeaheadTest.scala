package com.keepit.typeahead.abook

import com.keepit.common.mail.EmailAddress
import com.keepit.model.EContact

import org.specs2.mutable.Specification

class EContactTypeaheadTest extends Specification {

  "EContactTypeaheadBase" should {
    "identity likely human email addresses" in {
      human("jared@kifi.com") === true
      human("chris.o'donnell@hollywood.com") === true
      human("abra@stanford.edu") === true
      human("ninja_turtle_babe2126@hotmail.com") === true
      human("Michelle.HernandezRosa@amc.com") === true
      human("arthur.droid@student.ecp.fr") === true
      human("ks3bp-3580038719@sale.craigslist.org") === true  // TODO: improve to get this right?
    }
    "identity likely non-human email addresses" in {
      human("TRACE@noreply.github.com") === false
      human("reply+i-7787194-41b5e3e2217dd430b85fa5a942e84e30772877d7-1576151@reply.github.com") === false
      human("x+7368498674275@mail.asana.com") === false
      human("r+0gkz6vfwb0F-hHX9aRoQxNS6_MxZaA0YWig@indeedmail.com") === false
      human("support+id1299341@dropbox.zendesk.com") === false
      human("8c29fb17e6e5823f5e760b60ca109ce5+8163495-8989571@inbound.postmarkapp.com") === false
      human("student+unsubscribe@cs.stanford.edu") === false
      human("m+82egqjq000000975o32002e4heuvwn41uj@reply.facebook.com") === false
      human("support+id3476888@ubercab.zendesk.com") === false
      human("post+4c7841a9bf168@ohlife.com") === false
      human("Nathaniel+Amarose-04791618235241941441-Bj9zsj96@prod.writely.com") === false
      human("unsubscribe@handl.it") === false
      human("no-reply@wordpress.com") === false
      human("bloggerdev-unsubscribe@yahoogroups.com") === false
      human("jobs-unsubscribe@perl.org") === false
      human("comp.lang.php-unsubscribe@googlegroups.com") === false
      human("dev-subscribe@subversion.tigris.org") === false
      human("thrift-user-subscribe@incubator.apache.org") === false
      human("msg-reply-8c2c4e9015b5d06c7ffa03608be226b5@reply.angel.co") === false
      human("unsubscribe@enews.rosewoodhotels.com") === false
      human("msg-reply-e8998cdf2ca161edbe70e8bf3d15d32f@reply.angel.co") === false
      human("pig-dev-unsubscribe@hadoop.apache.org") === false
      human("messages-noreply@linkedin.com") === false
      human("support+id32041@ubercab.zendesk.com") === false
      human("2c513ox7dnruuaeamshk71vj5i62@reply.airbnb.com") === false
    }
  }

  private def human(addr: String): Boolean = {
    EContactTypeaheadBase.isLikelyHuman(EContact(userId = null, email = EmailAddress(addr)))
  }

}
