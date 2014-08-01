package com.keepit.eliza.mail

import com.keepit.test.ElizaTestInjector
import org.specs2.mutable.Specification
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.eliza.FakeElizaExternalEmailModule

class MailMessageReceiverPluginTest extends Specification with ElizaTestInjector {

  "MailDiscussionMessageParser" should {
    "be successfully instantiated" in {
      withDb(FakeCryptoModule(), FakeElizaExternalEmailModule()) { implicit injector =>
        inject[MailDiscussionMessageParser]
        1 === 1
      }
    }

    "parse email contents" in {
      val email1 =
        """
          |Testing
          |Testing sdlkfjslkdfjslfdjs.
          |Testing
          |
          |Testing
          |
          |Martin
          |
          |
          |On Mon, May 12, 2014 at 9:46 AM, Martin Raison (via Kifi) <
          |discuss+69debfd62c33430198970709a445ef83@kifi.com> wrote:
          |
          |>        Martin Raison, martin+test1@kifi.com,
          |> martinraison+test123456@gmail.com, martinraison+test13243@gmail.comcommen=
          |ted on this
          |> page<http://email.kifi.com/wf/click?upn=3Dy4u2sddLshGXnx1IledRTMuopzQbVxc=
          |lGLA43Sa7ZSfTatxdmJ-2FGauCUbL-2FQJD6f-2FPXjLJIckMEs8RSDigg7UQ-3D-3D_GjRFCNG=
          |dMNqdt7rSVIqdH4620M6hNLMVYMZn8Fa2TTijlzKgyBl-2F3RH3CmksY38ZrQmnw3kAHZbr5iH7=
          |UMC-2Fd-2Bfn1xlL4FEJOq-2BfnXvY6oj00WeKXdwhvgmJwZni89Hi9HMCanwuoJhNTo9E5YdPz=
          |
        """.stripMargin

      val email2 =
        """
          |This is another test
          |
          |With slightly different text, but more importantly, with a client using
          |another language
          |
          |
          |2014-05-14 11:28 GMT-07:00 Martin Raison (via Kifi) <
          |discuss+48632784fd9f4673837fd728e957830e@kifi.com>:
          |
          |>        Martin Raison, martin+test1@kifi.com,
          |> martinraison+test123456@gmail.com, martinraison+test13243@gmail.comcommen=
          |ted on this
          |> page<http://email.kifi.com/wf/click?upn=3Dy4u2sddLshGXnx1IledRTMuopzQbVxc=
          |lGLA43Sa7ZSfTatxdmJ-2FGauCUbL-2FQJD6f-2FPXjLJIckMEs8RSDigg7UQ-3D-3D_GjRFCNG=
          |dMNqdt7rSVIqdH-2BEnYA-2BRVQASoqR2kyz8PRmD5Q3XbdHKizPSCkIlLZB-2FjyKobklakjzf=
          |0WbtMyLpzXQdRfdySZdLlOg7cMsahKA9MD2zk1rENU0bIdCSztUTuC-2B-2FeFCQ7Bnp83AZJD-=
          |2B6jfh7qGLBhD9HZfWZfMejkLK2CV8umviP570mTCa39djRePX75OgC3eaB-2BRFYnEW9g-2FCV=
          |dvw96-2Fz5WX08y5QfR2u2DLXH0jRLaozKAqR-2BuTVbLVH3Ts6ysqgRJxdgsnlB5DT9-2FDay3=
          |WKZm9qegDc3WhggX-2BYKyJdFh9LwYaXYNwMRtqiMEp6Dp0KF2hwmkOdtOlXwkoA6rn4GZyrJ3h=
          |m1sR8-3D>using
          |>
        """.stripMargin

      val email3 =
        """
          |Replying to the test
          |
          |Sent from my iPhone
          |
          |Le 14 mai 2014 =C3=A0 11:40, "Martin Raison (via Kifi)" <
          |discuss+8f7b02e484fc4a0e908f3a84a1500681@kifi.com> a =C3=A9crit :
          |
          |  Kifi       Martin Raison, martinraison+test2322@gmail.com commented on th=
          |is
          |page<http://email.kifi.com/wf/click?upn=3DThEa7iDS1Z7Im7BdHicWYgtk3GwQOYBvS=
          |JMsJ9WiphRpDdt9WuNOPm84wS42A2z0-2BomgW9iJQNiNlPfX2nM5YA-3D-3D_GjRFCNGdMNqdt=
          |7rSVIqdH-2FfbBo00oaTTfVY3I9gHeqNHAHMOd5R1Qt-2F4cfZE7D7Mm2luEYwGuPKcsqcadmc9=
          |notP-2Fqn6Nd8d2J6G4TOo0IL2vIChCNqRZnT8sBa-2BrIS8ywew5PrZN-2FKmC6VZRthFRM-2B=
          |RrJhq6WgYR8nh1eDgEW2w-2FTkDogFRGXTz86-2FaEAC64pgpHBep1YTEcjIUahQQ2-2BD51l5K=
          |CFKxiaj83-2B956f6-2F9i0jdUUpeJnfjkPZDYn7jcQ9VTr-2FX0UL6M12iSECY4Lk0Eaw2IIyg=
          |VzwamEFI1g5c1SBNDMsErepK6y00okiqXkq1BNaKZKaDwh-2F1zmRNNo8LF4BZW2EUTwQWNgwwG=
          |8-3D>using
          |Kifi<http://email.kifi.com/wf/click?upn=3Dv2BxTv9bjE3kWga-2B0-2Fqf92EpkqmCJ=
          |wuA30g8rCCGfmw-3D_GjRFCNGdMNqdt7rSVIqdH-2FfbBo00oaTTfVY3I9gHeqNHAHMOd5R1Qt-=
          |2F4cfZE7D7Mm2luEYwGuPKcsqcadmc9notP-2Fqn6Nd8d2J6G4TOo0IL2vIChCNqRZnT8sBa-2B=
          |rIS8ywew5PrZN-2FKmC6VZRthFRM-2BRrJhq6WgYR8nh1eDgEW1AkGP8XufMKESSt68L7Ofx974=
          |e3FvW2a5-2FcpoX4uUHDr41Y5tZkZ1qkr-2BSpUCKtz8ced-2B5oSd2AE5zmUUpvdETpHRgB8j5=
          |Otj7crqJgThReMUEg68zeTw12GbkaFaT8S5-2FsSHXb9dpxn2lIW-2FQ8HebARcXz1cXLmiCGHp=
          |1E8vh629-2BPg42Y3O90nP6BRPCPyM-3D>
          |
        """.stripMargin

      val outlookEmail =
        """
          |Great! (my first email from outlook.com)
          |
          |From: discuss+43c8e5ab65a74268b7fbee31ce11ffd1@kifi.com
          |To: arandomguy@outlook.com
          |Subject: Stylus - expressive=2C robust=2C feature-rich CSS preprocessor
          |Date: Wed=2C 28 May 2014 20:22:34 +0000
          |
          |=0A=
          |=0A=
          |=0A=
          |=0A=
          |=0A=
          |  =0A=
          |  =0A=
          |  =0A=
          |  kifi=0A=
        """.stripMargin

      val zimbraEmail =
        """
          |Testing Zimbra webmail
          |
          |----- Original Message -----
          |From: "Marvin Adams (via Kifi)" <discuss+c7958e8b69564b519f7e8237b0529348@kifi.com>
          |To: marvinadams@stanford.edu
          |Sent: Wednesday, May 28, 2014 1:40:09 PM
          |Subject: user-select
        """.stripMargin

      MailDiscussionMessageParser.extractMessage(email1) ===
        """Testing
          |Testing sdlkfjslkdfjslfdjs.
          |Testing
          |
          |Testing
          |
          |Martin""".stripMargin

      MailDiscussionMessageParser.extractMessage(email2) ===
        """This is another test
          |
          |With slightly different text, but more importantly, with a client using
          |another language""".stripMargin

      MailDiscussionMessageParser.extractMessage(email3) === "Replying to the test"

      MailDiscussionMessageParser.extractMessage(outlookEmail) === "Great! (my first email from outlook.com)"

      MailDiscussionMessageParser.extractMessage(zimbraEmail) === "Testing Zimbra webmail"
    }
  }

}
