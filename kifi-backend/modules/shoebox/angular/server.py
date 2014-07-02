#!/usr/bin/python

#######################################################################
# Simple Python server - redirects non-static routes to index.html    #
# See https://coderwall.com/p/_6ms_g                                  #
#######################################################################

import SimpleHTTPServer, SocketServer, urlparse, os, sys

DEFAULT_PORT = 8080

class Handler( SimpleHTTPServer.SimpleHTTPRequestHandler ):
    def do_GET( self ):
        urlParams = urlparse.urlparse(self.path)
        if urlParams.path != '/' and os.access( '.' + os.sep + urlParams.path, os.R_OK ):
            SimpleHTTPServer.SimpleHTTPRequestHandler.do_GET(self);
        else:
            self.send_response(200)
            self.send_header( 'Content-type', 'text/html' )
            self.end_headers()
            self.wfile.write( open('dev.html').read() )

port = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT
httpd = SocketServer.TCPServer( ('0.0.0.0', port), Handler )

print "Server started on port %d" % port
try:
  httpd.serve_forever()
except KeyboardInterrupt:
  print "Closing server"
  httpd.socket.close()
