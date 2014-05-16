import csv
from urlparse import urlparse
import sys
import operator

"""
  Takes a .tsv file as input, containing actual site names in the first column
  and domain names or urls in the second column, and outputs a domain -> name
  map in Scala format, with duplicates and removed and 'www' stripped off where
  necessary. Entries are sorted alphabetically by site name.
"""

def transform(source, dest):
  with open(source, 'rb') as csvfile, open(dest, 'w') as destfile:
    reader = csv.reader(csvfile, delimiter='\t')
    reader.next()
    names = {}
    for row in reader:
      nUrl = urlparse(row[1].lower())
      domain = nUrl.netloc if nUrl.netloc else nUrl.path
      wwwPref = 'www.'
      if domain.startswith(wwwPref):
        domain = domain[len(wwwPref):]
      names[domain] = row[0]
    l = sorted(names.iteritems(), key=lambda tup: tup[1].lower())
    string = ',\n'.join('"%s" -> "%s"' % (domain, name) for domain,name in l)
    destfile.write(string)

if __name__ == "__main__":
  if len(sys.argv) < 3:
    print "Usage: python %s source dest" % sys.argv[0]
  else:
    transform(sys.argv[1], sys.argv[2])
