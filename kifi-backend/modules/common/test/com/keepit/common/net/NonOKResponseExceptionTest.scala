package com.keepit.common.net

import org.specs2.mutable.Specification
import com.keepit.common.service.ServiceUri
import com.keepit.common.zookeeper._
import com.keepit.common.service._

class NonOKResponseExceptionTest extends Specification {
  "NonOKResponseException" should {
    "have short message with DirectUrl" in {
      val exception = NonOKResponseException(
        DirectUrl("http://commons.apache.org/proper/commons-lang/javadocs/api-3.1/org/apache/commons/api-3.1/org/apache/commons/api-3.1/org/apache/commons/api-3.1/org/apache/commons/lang3/StringUtils.html#abbreviate(java.lang.String, int)"),
        FakeClientResponse(
          """Sportsman delighted improving dashwoods gay instantly happiness six. Ham now amounted absolute not mistaken way pleasant whatever. At an these still no dried folly stood thing. Rapid it on hours hills it seven years. If polite he active county in spirit an. Mrs ham intention promotion engrossed assurance defective. Confined so graceful building opinions whatever trifling in. Insisted out differed ham man endeavor expenses. At on he total their he songs. Related compact effects is on settled do.
            |
            |Ignorant branched humanity led now marianne too strongly entrance. Rose to shew bore no ye of paid rent form. Old design are dinner better nearer silent excuse. She which are maids boy sense her shade. Considered reasonable we affronting on expression in. So cordial anxious mr delight. Shot his has must wish from sell nay. Remark fat set why are sudden depend change entire wanted. Performed remainder attending led fat residence far.
            |
            |To sorry world an at do spoil along. Incommode he depending do frankness remainder to. Edward day almost active him friend thirty piqued. People as period twenty my extent as. Set was better abroad ham plenty secure had horses. Admiration has sir decisively excellence say everything inhabiting acceptance. Sooner settle add put you sudden him.
            |To sorry world an at do spoil along. Incommode he depending do frankness remainder to. Edward day almost active him friend thirty piqued. People as period twenty my extent as. Set was better abroad ham plenty secure had horses. Admiration has sir decisively excellence say everything inhabiting acceptance. Sooner settle add put you sudden him.
            |Day handsome addition horrible sensible goodness two contempt. Evening for married his account removal. Estimable me disposing of be moonlight cordially curiosity. Delay rapid joy share allow age manor six. Went why far saw many knew. Exquisite excellent son gentleman acuteness her. Do is voice total power mr ye might round still.
            |Day handsome addition horrible sensible goodness two contempt. Evening for married his account removal. Estimable me disposing of be moonlight cordially curiosity. Delay rapid joy share allow age manor six. Went why far saw many knew. Exquisite excellent son gentleman acuteness her. Do is voice total power mr ye might round still.
            |Built purse maids cease her ham new seven among and. Pulled coming wooded tended it answer remain me be. So landlord by we unlocked sensible it. Fat cannot use denied excuse son law. Wisdom happen suffer common the appear ham beauty her had. Or belonging zealously existence as by resources.
            |Built purse maids cease her ham new seven among and. Pulled coming wooded tended it answer remain me be. So landlord by we unlocked sensible it. Fat cannot use denied excuse son law. Wisdom happen suffer common the appear ham beauty her had. Or belonging zealously existence as by resources.
            |Open know age use whom him than lady was. On lasted uneasy exeter my itself effect spirit. At design he vanity at cousin longer looked ye. Design praise me father an favour. As greatly replied it windows of an minuter behaved passage. Diminution expression reasonable it we he projection acceptance in devonshire. Perpetual it described at he applauded.""".stripMargin),
        Some("""Specifically: If str is less than maxWidth characters long, return it.
              Else abbreviate it to (substring(str, 0, max-3) + "...").
              If maxWidth is less than 4, throw an IllegalArgumentException.
              In no case will it return a String of length greater than maxWidth.
               StringUtils.abbreviate(null, *)      = null
               StringUtils.abbreviate("", 4)        = ""
               StringUtils.abbreviate("abcdefg", 6) = "abc..."
               StringUtils.abbreviate("abcdefg", 7) = "abcdefg"
               StringUtils.abbreviate("abcdefg", 8) = "abcdefg"
               StringUtils.abbreviate("abcdefg", 4) = "a..."
               StringUtils.abbreviate("abcdefg", 3) = IllegalArgumentException"""))
      println(exception.getMessage().size)
      exception.getMessage().size === 1123
    }

    "have short message with ServiceUri" in {
      val remoteService1 = RemoteService(null, ServiceStatus.UP, ServiceType.TEST_MODE)
      val instance = new ServiceInstance(Node("/node_00000001"), false, remoteService1)
      val exception = NonOKResponseException(
        new ServiceUri(instance, null, -1, "/this/is/the/path/and/it/may/be/very/very/long/so/it/must/be/chopped/a/bit/if/you/know/what/i/mean/this/is/the/path/and/it/may/be/very/very/long/so/it/must/be/chopped/a/bit/if/you/know/what/i/mean/this/is/the/path/and/it/may/be/very/very/long/so/it/must/be/chopped/a/bit/if/you/know/what/i/mean/this/is/the/path/and/it/may/be/very/very/long/so/it/must/be/chopped/a/bit/if/you/know/what/i/mean"),
        FakeClientResponse("short response"),
        Some("short body"))
      exception.getMessage() === "[TM1] ERR on TM1:/this/is/the/path/and/it/may/be/very/very/long/so/it/must/be/chopped/a/bit/if/you/know/what/i/mea... stat:200 - short response]"
    }
  }
}
