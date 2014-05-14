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
}
