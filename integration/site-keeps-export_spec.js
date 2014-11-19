var frisby = require('frisby'); // http://frisbyjs.com/
var headers = require('./auth_headers.js')

var expectedExportString = '<!DOCTYPE NETSCAPE-Bookmark-file-1>\n\
<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">\n\
<!--This is an automatically generated file.\n\
It will be read and overwritten.\n\
Do Not Edit! -->\n\
<Title>Kifi Bookmarks Export</Title>\n\
<H1>Bookmarks</H1>\n\
<DL>\n\
<DT><A HREF="https://www.kifi.com/friends/invite" ADD_DATE="1403808819" TAGS="kifi Support">kifi • Find friends your friends on kifi</A>\n\
<DT><A HREF="http://support.kifi.com/customer/portal/emails/new" ADD_DATE="1403808820" TAGS="kifi Support">kifi • Contact Us</A>\n\
<DT><A HREF="http://support.kifi.com/customer/portal/articles/1397866-introduction-to-kifi-" ADD_DATE="1403808821" TAGS="kifi Support">kifi • How to Use kifi</A>\n\
<DT><A HREF="https://www.kifi.com/install" ADD_DATE="1403808822" TAGS="kifi Support">kifi • Install kifi on Firefox and Chrome</A>\n\
<DT><A HREF="http://www.youtube.com/watch?v=_OBlgSz8sSM" ADD_DATE="1403808823" TAGS="Example Keep,Funny">Charlie bit my finger - again ! - YouTube</A>\n\
<DT><A HREF="http://twistedsifter.com/2013/01/50-life-hacks-to-simplify-your-world/" ADD_DATE="1403808824" TAGS="Example Keep,Read Later">50 Life Hacks to Simplify your World «TwistedSifter</A>\n\
<DT><A HREF="https://www.airbnb.com/locations/san-francisco/mission-district" ADD_DATE="1403808825" TAGS="Example Keep,Travel">Mission District, San Francisco Guide - Airbnb Neighborhoods</A>\n\
<DT><A HREF="http://www.amazon.com/Hitchhikers-Guide-Galaxy-25th-Anniversary/dp/1400052920/" ADD_DATE="1403808826" TAGS="Example Keep,Shopping Wishlist">The Hitchhiker\'s Guide to the Galaxy, 25th Anniversary Edition: Douglas Adams: 9781400052929: Amazon.com: Books</A>\n\
<DT><A HREF="http://joythebaker.com/2013/12/curry-hummus-with-currants-and-olive-oil/" ADD_DATE="1403808827" TAGS="Example Keep,Recipe">Joy the Baker – Curry Hummus with Currants and Olive Oil</A>\n\
</DL>';

frisby.globalSetup({
  request: {
    headers: {},
    inspectOnFailure: true,
    json: true
  },
  timeout: 10000
});

frisby.create('export keeps for download (api.kifi.com/site/keeps/export)')
  .get('https://api.kifi.com/site/keeps/export')
  .addHeaders(headers.userB) //for authentication
  .expectStatus(200)
  .expectHeaderContains('content-type', 'text/html')
  .expectBodyContains(expectedExportString)
.toss();
