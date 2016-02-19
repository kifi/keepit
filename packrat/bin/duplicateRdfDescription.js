#!/usr/bin/env node
var runningAsScript = (require.main === module);
var fs = require('fs');
var es = require('event-stream');
var xml2js = require('xml2js');
var infile = process.argv[2];
var outfile = process.argv[3];
var unlisted = (process.argv[4] === 'unlisted');

var inStream = (!infile || infile === '-' ? process.stdin : fs.createReadStream(infile, { flags: 'r' }));
var outStream = (!outfile || outfile === '-' ? process.stdout : fs.createWriteStream(outfile));

if (runningAsScript) {
  inStream
  .pipe(duplicateRdfDescription({ unlisted: unlisted }))
  .pipe(outStream);
}

module.exports = duplicateDescription;

function duplicateRdfDescription(options) {
  options = options || {};
  options.unlisted = unlisted || (typeof options.unlisted === 'boolean' ? options.unlisted : false);

  return es.pipeline(
    es.wait(),
    es.map(parseXml),
    es.map(duplicateDescription.bind(null, unlisted)),
    es.map(buildXml)
  );
}

function parseXml(rdfString, callback) {
  var parser = new xml2js.Parser();
  parser.parseString(rdfString, function (err, result) {
    callback(err, err ? '' : JSON.stringify(result));
  });
}

function duplicateDescription(unlisted, rdfJson, callback) {
  var root = JSON.parse(rdfJson);
  var descriptions = root.RDF.Description;
  var originalDescription = descriptions[0];
  var id = (unlisted ? 'kifi-unlisted@42go.com' : 'kifi@42go.com');
  var copiedDescription;

  if (descriptions.length === 1) {
    copiedDescription = copyJson(originalDescription);
    setDescriptionId(copiedDescription, id);
    setDescriptionId(originalDescription, id);
    copiedDescription.$.about = copiedDescription.$.about.replace(/kifi@42go\.com/g, 'kifi-unlisted@42go.com');
    descriptions.push(copiedDescription);
  }

  callback(null, JSON.stringify(root));
}

function setDescriptionId(description, id) {
  description['em:updates'][0].Seq[0].li[0].Description[0]['em:id'] = id;
}

function buildXml(rdfJson, callback) {
  var rdfObj = JSON.parse(rdfJson);

  var builder = new xml2js.Builder();
  var xml = builder.buildObject(rdfObj);

  callback(null, xml);
}

function copyJson(obj) {
  return JSON.parse(JSON.stringify(obj));
}
