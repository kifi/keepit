package com.keepit.eliza.util

import org.specs2.mutable.Specification

class MessageFormatterTest extends Specification {
  "MessageFormatter.toText" should {
    "remove look here v1 links" in {
      MessageFormatter.toText("""Hey [look here](x-kifi-sel:body>div#foo.bar:nth-child\(3\)) man! [rad](x-kifi-sel:body>a#k.m)""") ===
        "Hey look here man! rad"
    }

    "remove long look here range links" in {
      MessageFormatter.toText("""[look here](x-kifi-sel:r|body%3Ediv%23mid%3Ediv.grid%3Ediv%23layout.bigBox.round.clearfix%3E""" +
        """div%23primary.column.collection548%3Ediv%23content.hentry.cms%3Ediv.entry-content|p%3Anth-child(9\)%3Estrong|0:0|""" +
        """p%3Anth-child(21\)|2:297|1.%20Get%20Out%20of%20The%20Way%1E%22%1FYeah%2C%20by%20the%20standards%20of%20the%20rest%20of%20the%20world""" +
        """%2C%20we%20over-trust.%20And%20so%20far%2C%20our%20results%20have%20been%20far%20better%2C%20because%20we%20carefully%20selected""" +
        """%20people%20who%20should%20be%20over-trusted%1F.%22%C2%A0%1F%E2%80%94%C2%A0%1FCharlie%20Munger%1EIn%20answering%20one%20question""" +
        """%20on%20Berkshire-subsidiaries%20this%20year%2C%20Buffett%20admitted%20that%20he%20was%20sometimes%20too%20slow%20to%20act%20on""" +
        """%20certain%20things%20like%20personnel%20changes.%1EBuffett%20is%20famous%20for%20his%20hands-off%20management%20style.%20He""" +
        """%20lets%20the%20CEOs%20of%20the%20companies%20run%20their%20show%3B%20he%20only%20asks%20that%20they%20send%20him%20the%20money""" +
        """%20they%20can't%20use.%1EGood%20people%20want%20to%20work%20with%20him%2C%20and%20this%20is%20important%20because%20most%20of""" +
        """%20the%20people%20running%20Berkshire%20subsidiaries%20are%20already%20wealthy.%20They%20don't%20%1Fhave%1F%20to%20come%20to""" +
        """%20work%3B%20they%20%1Fwant%1F%20to%20come%20to%20work.%20And%20there%20is%20a%20huge%20difference.%20The%20only%20time%20you""" +
        """%20can%20get%20away%20with%20a%20crappy%20bureaucracy%20and%20a%20culture%20of%20distrust%20is%20when%20people%20%1Fhave%1F""" +
        """%20to%20come%20to%20work.%1EBuffett%20leaves%20these%20guys%20alone.%1EEvery%20now%20and%20then%20something%20happens%20at%20a""" +
        """%20Berkshire%20company%20that%20calls%20into%20question%20his%20near%20abdication%20of%20responsibility%20to%20a%20subsidiary.""" +
        """%20%22If%20only%20he%20had%20been%20paying%20attention%2C%22%20the%20critics%20chirp%2C%20%22this%20wouldn't%20have%20happened.%22""" +
        """%20Those%20critics%20are%20idiots.%20The%20alternative%C2%A0approaches%20are%20worse%2C%20not%20better.%1EThere%20are%20many""" +
        """%20positives%20to%20the%20approach%20Buffett%20takes.%1EIf%20Buffett%20closely%20managed%20each%20of%20his%20subsidiary%20CEOs""" +
        """%20to%20the%20point%20where%20most%20bosses%20manage%20their%20subordinates%2C%20they'd%20probably%20quit.%20If%20he%20sent%20out""" +
        """%20memos%20telling%20them%20all%20to%20use%20a%20new%20corporate%20HR%20system%2C%20they'd%20stop%20wanting%20to%20come%20to""" +
        """%20work.%20If%20he%20peppered%20them%20with%20relentless%20emails%20from%20%22headquarters%22%20on%20some%20new%20policy%2C%20they'd""" +
        """%20...%20well%20how%20do%20you%20feel%20about%20all%20of%20this%20stuff%3F%1EWith%20Berkshire%2C%20Buffett%20wanted%20to%20do%20things""" +
        """%20his%20way.%20He%20wanted%20to%20paint%20his%20own%20canvas.%20He%20didn't%20want%20other%20people%20telling%20him%20to%20add%20a""" +
        """%20little%20more%20blue%20here%20and%20take%20away%20a%20little%20red%20there.%20Most%20people%20want%20to%20run%20their%20own%20show.""" +
        """%20And%20the%20best%20part%3F%20This%20system%20gets%20more%20out%20of%20people%20than%20micromanaging.%1ESure%20sometimes%20things""" +
        """%20go%20wrong%2C%20but%20for%20the%20most%20part%2C%20the%20outcome%20is%20positively%20skewed.%20Things%20go%20wrong%20in%20other""" +
        """%20corporate%20cultures%20too%2C%20they%20are%20not%20immune.%1EWhen%20things%20go%20wrong%20in%20bureaucratic%20cultures%2C%20however""" +
        """%2C%20it's%20nearly%20impossible%20to%20hold%20anyone%20accountable%20because%20no%20one%20is%20really%20responsible%20for%20anything.""" +
        """%20And%20it's%20hard%20to%20hold%20people%20accountable%20when%20they%20are%20not%20responsible.%20It's%20a%20seductive%20illusion""" +
        """%20to%20think%20we%20can%20create%20a%20system%20where%20people%20can't%20mess%20up.%20Buffett's%20hand's%20off%20approach%20makes""" +
        """%20it%20clear%20who%20is%20responsible%20for%20what.%20And%20this%20approach%2C%20not%20stock%20options%2C%20creates%20a%20%1Freal%1F""" +
        """%20ownership%20culture.%1EThis%20system%20also%20frees%20up%20Buffett's%20time.%20He%20doesn't%20have%20to%20chase%20management""" +
        """%20details%2C%20read%20power-points%2C%20etc.%20He%20can%20%1Fsit%20and%20read%20and%20think%1F%20%E2%80%94%20that%20means%20he""" +
        """%20does%20what%20he%20does%20best.%20And%20judging%20by%20the%20results%2C%20this%20has%20worked%20out%20well.%20Hiring%20the""" +
        """%20right%20people%20and%20largely%20staying%20out%20of%20the%20way%20is%20incredibly%20underrated%20and%20yet%20nearly%20impossible""" +
        """%20to%20find%20in%20large%20established%20bureaucratic%20organizations.%20Yet%20as%20Buffett%20shows%2C%20it's%20a%20much%20b)""") ===
        "look here"
    }

    "remove look here range link whose quoted text ends in a backslash" in {
      MessageFormatter.toText("""([hi](x-kifi-sel:r|body%3Ediv%23mid|0:0|p%3Anth-child(21\)|2:29|p%3Anth-child(22\)|some%20long%20line%20of%20code%5C)!)""") ===
        "(hi!)"
    }

    "remove look here image link" in {
      MessageFormatter.toText("""[look here](x-kifi-sel:i|body%3Ediv%23content.outside%3Ediv.panel.left%3Ediv%23imagelist%3Ediv.posts%3Ediv""" +
        """%23myu96zP.post%3Ea%3Eimg|160x160|135x135|http%3A%2F%2Fi.imgur.com%2Fmyu96zPb.jpg)""") ===
        "look here"
    }
  }

  "MessageFormatter.parseMessage" should {
    "parse message text containing one range look-here link with a long quotation" in {
      val msg1 = raw"""Check this out. [look here](x-kifi-sel:r|body%3Ediv%23page%3Ediv%23main%3Ediv.mainbar.for_answers%3Ediv%23question_retrieve_container%3Ediv%23answers%3Ediv%23answer_item_list.widget_value_list%3Eul.value_list%3Eli%23answer_item_list_item_272.value_list_item%3Ediv%23answer_update_form_272.answer_content_container%3Ediv.answer_text%3Ediv|p%3Anth-child(1\)|0:1|ul%3Anth-child(13\)%3Eli%3Anth-child(3\)|0:110|I%20tried%20this%20with%20a%20friend%20once%20and%20it%20worked%20great.%20My%20solution%20assumes%20you%20and%20your%20collaborator%20are%20using%20OS%20X%20with%20wireless%20network%20cards%2C%20and%20that%20you%20are%20using%20Live%208.2.2.%1E%20%C2%A0%1E%201.%20Create%20a%20wireless%20network%1E%20from%20System%20Preferences%20select%20'Network'%1E%20click%20the%20'%2B'%20sign%20at%20the%20bottom%20left%20of%20the%20network%20interface%20list%1E%20select%20'AirPort'%20from%20the%20'Interface'%20drop-down%20menu%2C%20and%20give%20it%20a%20name%20such%20as%20'Network%20for%20Live%20collaboration'%2C%20then%20click%20'Create'%1E%20back%20in%20the%20Network%20panel%2C%20select%20'Create%20Network...'%1E%20give%20the%20network%20a%20unique%20name%20and%20click%20'OK'%20(although%20not%20required%2C%20you%20should%20probably%20also%20select%20the%20'Require%20Password'%20checkbox%20and%20give%20a%20suitable%20password\)%1E%20%C2%A0%1E%202.%20Setup%20the%20MIDI%20host%1E%20start-up%20Audio%20MIDI%20Setup%20(%2FApplications%2FUtilities%2FAudio%20MIDI%20Setup.app\)%1E%20click%20the%20'Window'%20menu%20and%20select%20'Show%20MIDI%20Window'%1E%20double-click%20the%20'Network'%20icon%1E%20under%20the%20'My%20Sessions'%20section%20click%20the%20'%2B'%20button%1E%20click%20the%20checkbox%20beside%20the%20session%20'Session%201'%1E%20note%20the%20name%20in%20the%20field%20labeled%20'Bonjour%20name'%20in%20the%20panel%20to%20the%20right%20of%20'My%20Sessions'%1E%20make%20sure%20'Anyone'%20is%20selected%20from%20the%20'Who%20may%20connect%20to%20me%3A'%20drop-down%20menu%20below%20the%20'Directory'%20listing%1E%20%C2%A0%1E%203.%20Setup%20the%20MIDI%20slave%1E%20connect%20to%20the%20wireless%20network%20you%20created%20earlier%20on%20the%20second%20computer%1E%20open%20'Audio%20MIDI%20Setup'%1E%20under%20'My%20Sessions'%20click%20the%20'%2B'%20button%1E%20check%20the%20checkbox%20beside%20the%20session%20'Session%201'%1E%20in%20the%20'Directory'%20section%20you%20should%20see%20the%20name%20in%20the%20'Bonjour%20name'%20text%20field%20you%20saw%20on%20the%20first%20computer%1E%20select%20the%20name%20from%20the%20'Directory'%20listing%20then%20click%20the%20'Connect'%20button%20below%20the%20section%1E%20%C2%A0%1E%204.%20Setup%20Live%20to%20sync%20over%20the%20network%1E%20start%20Live%1E%20from%20the%20preferences%20select%20the%20'MIDI%20Sync'%20tab%1E%20ensure%20'Sync'%20is%20set%20to%20'On'%20for%20'Input'%20on%20the%20slave%20computer%2C%20and%20'On'%20for%20'Output'%20for%20the%20master%20computer)"""
      val segments = MessageFormatter.parseMessageSegments(msg1)
      segments.length === 2
      segments(0) === TextSegment("Check this out. ")
      segments(1) must haveClass[TextLookHereSegment]
    }

    "parse message text containing two range look-here links with escaped right brackets and right parentheses" in {
      val msg = """check [t[hi\]s](x-kifi-sel:r|body|p.a|0:1|p.b|0:4|f(o\)o) and [t[ha\]t](x-kifi-sel:r|body>div:nth-child(1\)|p.a|0:6|p.b|0:9|b(a\)r)."""
      MessageFormatter.parseMessageSegments(msg) === Seq(
        TextSegment("check "),
        TextLookHereSegment("t[hi]s", """f(o)o"""),
        TextSegment(" and "),
        TextLookHereSegment("t[ha]t", """b(a)r"""),
        TextSegment("."))
    }

    "parse message text containing two consecutive range look-here links" in {
      val msg = """check [t[hi\]s](x-kifi-sel:r|body|p.a|0:1|p.b|0:4|f(o\)o)[t[ha\]t](x-kifi-sel:r|body>div:nth-child(1\)|p.a|0:6|p.b|0:9|b(a\)r)."""
      MessageFormatter.parseMessageSegments(msg) === Seq(
        TextSegment("check "),
        TextLookHereSegment("t[hi]s", """f(o)o"""),
        TextLookHereSegment("t[ha]t", """b(a)r"""),
        TextSegment("."))
    }

    "parse message text starting with a range look-here link" in {
      val msg = """[t[hi\]s](x-kifi-sel:r|body|p.a|0:1|p.b|0:4|f(o\)o)[t[ha\]t](x-kifi-sel:r|body>div:nth-child(1\)|p.a|0:6|p.b|0:9|b(a\)r)some text"""
      MessageFormatter.parseMessageSegments(msg) === Seq(
        TextLookHereSegment("t[hi]s", """f(o)o"""),
        TextLookHereSegment("t[ha]t", """b(a)r"""),
        TextSegment("some text"))
    }

    "parse message text containing just a range look-here link" in {
      val msg = """[t[hi\]s](x-kifi-sel:r|body|p.a|0:1|p.b|0:4|f(o\)o)"""
      MessageFormatter.parseMessageSegments(msg) === Seq(TextLookHereSegment("t[hi]s", """f(o)o"""))
    }

    "parse message text containing only text" in {
      val msg = """check this out"""
      MessageFormatter.parseMessageSegments(msg) === Seq(TextSegment("check this out"))
    }
  }
}
