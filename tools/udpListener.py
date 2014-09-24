import socket
import sys
port = int(sys.argv[1])
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(('', port))
print 'Address: {}:{}'.format(socket.gethostbyname(socket.gethostname()), port)
while 1:
  data, addr = s.recvfrom(1024)
  print data
