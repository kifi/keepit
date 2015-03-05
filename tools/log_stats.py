# -*- coding: utf-8 -*-
"""
Created on Mon Mar  2 12:26:30 2015

@author: yingjie
"""

import requests
import socket
import json
import re

pat = re.compile("location:\[class\s(.*)\]\[method\s(.*)\]:(\d{1,5})")

def report(stats, topK = 20):
    report = {}
    for k in stats.keys():
        lst = stats[k]
        if len(lst) > 4:
            ## total duration, size, min, max, 50%, 90%, 99% percentile
            lst.sort()
            n = len(lst)
            (id1, id2, id3) = (int(.5 * n), int(.9 * n), int(.99 * n))
            report[k] = (sum(lst), len(lst), lst[0], lst[n-1], lst[id1], lst[id2], lst[id3])
    rep_items = report.items()
    rep_items.sort(key = lambda x: -x[1][0])
    lines = []
    lines.append(' ' * 89 + '{:<5s} | {:<5s} | {:<5s} | {:5s}'.format('min', '50%', '90%', '99%', 'max'))
    for i in range(min(topK, len(rep_items))):
        (k, v) = rep_items[i]
        # duration sum size min | 50% | 90% | 99% | max
        ln = "{:<60} sum: {:<9d} size: {:<6d} ".format(k, v[0], v[1]) + \
        "{:<5d} | {:<5d} | {:<5d} | {:<5d}".format(v[2], v[4], v[5], v[6], v[3])
        lines.append(ln)
    return lines


def db_log_stats(fpath):
    lines = open(fpath, 'rb')
    raw_stats = {}
    for ln in lines:
        if "duration" in ln:
            line = ln.strip()
            parts = line.split('\t')
            cmd = parts[6].strip()
            try:
                gps = re.match(pat, cmd).groups()
                cmd = '.'.join(gps)
            except:
                pass
            dur = int(parts[4].split(':')[1])
            points = raw_stats.get(cmd, [])
            points.append(dur)
            raw_stats[cmd] = points
    lines = report(raw_stats, topK = 20)
    return lines

def access_log_stats(fpath):
    lines = open(fpath, 'rb')
    raw_stats = {}
    for ln in lines:
        if "HTTP_IN" in ln and 'duration' in ln and 'url:' in ln:
            line = ln.strip()
            parts = line.split('\t')
            w = {}
            for p in parts:
                if ':' in p:
                    pos = p.find(':')
                    w[p[0:pos].strip()] = p[pos + 1:].strip()
            cmd = w['url']
            para = cmd.find('?')
            if para > 0:
                cmd = cmd[0: para]
            dur = int(w['duration'])
            points = raw_stats.get(cmd, [])
            points.append(dur)
            raw_stats[cmd] = points
    lines = report(raw_stats, topK = 20)
    return lines


def main():
    res = requests.get('http://localhost:9000/internal/clusters/mystatus')
    status = res.content
    host_name = socket.gethostname() + '\t' + status
    base = '/home/fortytwo/run/shoebox/log/'
    dblog = base + 'db.log'
    access_log = base + 'access.log'
    url = 'https://hooks.slack.com/services/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'
    headers = {'content-type': 'application/json'}
    payload = {"channel": "#pulse", "username": "db", "text": "dummy performance stats", "icon_emoji": ":bomb:"}

    lines = db_log_stats(dblog)
    msg = "=====BEGIN DB LOG REPORT=====\n\n```" + host_name + '\n' + '\n'.join(lines[0:20]) + "```\n\n=====END REPORT====="
    payload['text'] = msg

    #f = open(base + 'db.log.stats', 'wb')
    #f.write(msg)
    #f.close()

    requests.post(url, data=json.dumps(payload), headers=headers)

    lines = access_log_stats(access_log)
    msg = "=====BEGIN Acess LOG REPORT=====\n\n```" + host_name + '\n' + '\n'.join(lines[0:20]) + "```\n\n=====END REPORT====="
    payload['text'] = msg
    #f = open(base + 'access.log.stats', 'wb')
    #f.write(msg)
    #f.close()
    requests.post(url, data=json.dumps(payload), headers=headers)

if __name__ =="__main__":
    main()
