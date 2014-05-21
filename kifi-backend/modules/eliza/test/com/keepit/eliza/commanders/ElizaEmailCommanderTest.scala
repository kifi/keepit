package com.keepit.eliza.commanders

import com.keepit.test.TestInjector
import org.specs2.mutable.Specification
import com.keepit.eliza.model.{ExtendedThreadItem, ThreadEmailInfo}

class ElizaEmailCommanderTest extends Specification with TestInjector {

  "ElizaEmailCommanderTest" should {
    "parse message text containing one range look-here link with a long quotation" in {
      val msg1 = raw"""Check this out. [look here](x-kifi-sel:r|body%3Ediv%23page%3Ediv%23main%3Ediv.mainbar.for_answers%3Ediv%23question_retrieve_container%3Ediv%23answers%3Ediv%23answer_item_list.widget_value_list%3Eul.value_list%3Eli%23answer_item_list_item_272.value_list_item%3Ediv%23answer_update_form_272.answer_content_container%3Ediv.answer_text%3Ediv|p%3Anth-child(1\)|0:1|ul%3Anth-child(13\)%3Eli%3Anth-child(3\)|0:110|I%20tried%20this%20with%20a%20friend%20once%20and%20it%20worked%20great.%20My%20solution%20assumes%20you%20and%20your%20collaborator%20are%20using%20OS%20X%20with%20wireless%20network%20cards%2C%20and%20that%20you%20are%20using%20Live%208.2.2.%1E%20%C2%A0%1E%201.%20Create%20a%20wireless%20network%1E%20from%20System%20Preferences%20select%20'Network'%1E%20click%20the%20'%2B'%20sign%20at%20the%20bottom%20left%20of%20the%20network%20interface%20list%1E%20select%20'AirPort'%20from%20the%20'Interface'%20drop-down%20menu%2C%20and%20give%20it%20a%20name%20such%20as%20'Network%20for%20Live%20collaboration'%2C%20then%20click%20'Create'%1E%20back%20in%20the%20Network%20panel%2C%20select%20'Create%20Network...'%1E%20give%20the%20network%20a%20unique%20name%20and%20click%20'OK'%20(although%20not%20required%2C%20you%20should%20probably%20also%20select%20the%20'Require%20Password'%20checkbox%20and%20give%20a%20suitable%20password\)%1E%20%C2%A0%1E%202.%20Setup%20the%20MIDI%20host%1E%20start-up%20Audio%20MIDI%20Setup%20(%2FApplications%2FUtilities%2FAudio%20MIDI%20Setup.app\)%1E%20click%20the%20'Window'%20menu%20and%20select%20'Show%20MIDI%20Window'%1E%20double-click%20the%20'Network'%20icon%1E%20under%20the%20'My%20Sessions'%20section%20click%20the%20'%2B'%20button%1E%20click%20the%20checkbox%20beside%20the%20session%20'Session%201'%1E%20note%20the%20name%20in%20the%20field%20labeled%20'Bonjour%20name'%20in%20the%20panel%20to%20the%20right%20of%20'My%20Sessions'%1E%20make%20sure%20'Anyone'%20is%20selected%20from%20the%20'Who%20may%20connect%20to%20me%3A'%20drop-down%20menu%20below%20the%20'Directory'%20listing%1E%20%C2%A0%1E%203.%20Setup%20the%20MIDI%20slave%1E%20connect%20to%20the%20wireless%20network%20you%20created%20earlier%20on%20the%20second%20computer%1E%20open%20'Audio%20MIDI%20Setup'%1E%20under%20'My%20Sessions'%20click%20the%20'%2B'%20button%1E%20check%20the%20checkbox%20beside%20the%20session%20'Session%201'%1E%20in%20the%20'Directory'%20section%20you%20should%20see%20the%20name%20in%20the%20'Bonjour%20name'%20text%20field%20you%20saw%20on%20the%20first%20computer%1E%20select%20the%20name%20from%20the%20'Directory'%20listing%20then%20click%20the%20'Connect'%20button%20below%20the%20section%1E%20%C2%A0%1E%204.%20Setup%20Live%20to%20sync%20over%20the%20network%1E%20start%20Live%1E%20from%20the%20preferences%20select%20the%20'MIDI%20Sync'%20tab%1E%20ensure%20'Sync'%20is%20set%20to%20'On'%20for%20'Input'%20on%20the%20slave%20computer%2C%20and%20'On'%20for%20'Output'%20for%20the%20master%20computer)"""
      val segments = ElizaEmailCommander.parseMessage(msg1)
      segments.length === 2
      segments(0) === TextSegment("Check this out. ")
      segments(1) must haveClass[TextLookHereSegment]
    }

    "parse message text containing two range look-here links with escaped right brackets and right parentheses" in {
      val msg = """check [t[hi\]s](x-kifi-sel:r|body|p.a|0:1|p.b|0:4|f(o\)o) and [t[ha\]t](x-kifi-sel:r|body>div:nth-child(1\)|p.a|0:6|p.b|0:9|b(a\)r)."""
      ElizaEmailCommander.parseMessage(msg) === Seq(
        TextSegment("check "),
        TextLookHereSegment("t[hi]s", """f(o)o"""),
        TextSegment(" and "),
        TextLookHereSegment("t[ha]t", """b(a)r"""),
        TextSegment("."))
    }

    "parse message text containing two consecutive range look-here links" in {
      val msg = """check [t[hi\]s](x-kifi-sel:r|body|p.a|0:1|p.b|0:4|f(o\)o)[t[ha\]t](x-kifi-sel:r|body>div:nth-child(1\)|p.a|0:6|p.b|0:9|b(a\)r)."""
      ElizaEmailCommander.parseMessage(msg) === Seq(
        TextSegment("check "),
        TextLookHereSegment("t[hi]s", """f(o)o"""),
        TextLookHereSegment("t[ha]t", """b(a)r"""),
        TextSegment("."))
    }

    "parse message text starting with a range look-here link" in {
      val msg = """[t[hi\]s](x-kifi-sel:r|body|p.a|0:1|p.b|0:4|f(o\)o)[t[ha\]t](x-kifi-sel:r|body>div:nth-child(1\)|p.a|0:6|p.b|0:9|b(a\)r)some text"""
      ElizaEmailCommander.parseMessage(msg) === Seq(
        TextLookHereSegment("t[hi]s", """f(o)o"""),
        TextLookHereSegment("t[ha]t", """b(a)r"""),
        TextSegment("some text"))
    }

    "parse message text containing just a range look-here link" in {
      val msg = """[t[hi\]s](x-kifi-sel:r|body|p.a|0:1|p.b|0:4|f(o\)o)"""
      ElizaEmailCommander.parseMessage(msg) === Seq(TextLookHereSegment("t[hi]s", """f(o)o"""))
    }

    "parse message text containing only text" in {
      val msg = """check this out"""
      ElizaEmailCommander.parseMessage(msg) === Seq(TextSegment("check this out"))
    }
  }

}
