#!/usr/bin/env python
# Script for creating Facebook test users (https://developers.facebook.com/docs/test_users/)
# Slightly modified version of code available at https://github.com/kcbanner/facebook-test-users

import sys
import urllib
import urllib2
import httplib
import json
from optparse import OptionParser

# Production
APP_ID = '104629159695560'
APP_SECRET = '352415703e40e9bb1b0329273fdb76a9'
# Dev
#APP_ID = '530357056981814'
#APP_SECRET = 'cdb2939941a1147a4b88b6c8f3902745'

GRAPH_URL = 'graph.facebook.com'

def get_access_token(app_id, app_secret):
    f = urllib2.urlopen("https://%s/oauth/access_token?client_id=%s&client_secret=%s&grant_type=client_credentials" % (GRAPH_URL, app_id, app_secret))
    return f.read()

def load_users(app_id, access_token):
    f = urllib2.urlopen("https://%s/%s/accounts/test-users?%s" % (GRAPH_URL, app_id, access_token))
    return json.loads(f.read())['data']

def create_user(app_id, access_token, installed=None, permissions=None):
    data = {}
    if installed is not None:
        data['installed'] = installed
    if permissions is not None and len(permissions) > 0:
        data['permissions'] = permissions

    url = "https://%s/%s/accounts/test-users?%s" % (GRAPH_URL, app_id, access_token)
    f = urllib2.urlopen(url, data=urllib.urlencode(data))
    return json.loads(f.read())

def delete_user(user_id, access_token):
    conn = httplib.HTTPSConnection(GRAPH_URL)
    conn.request('DELETE', "/%s?%s" % (user_id, access_token))
    r = conn.getresponse()
    return r.read() == 'true'

def set_user_password(user_id, password):
    url = "https://%s/%s"
    try:
        d1 = {'access_token': user_id['access_token'], 'password': password}
        f1 = urllib2.urlopen(url % (GRAPH_URL, user_id['id']), data=urllib.urlencode(d1))
        content = f1.read()

        if content == 'true':
            return 'New password of "%s" for %s set.' % (password, user_id['id'],)
        else:
            return 'Error:Password for %s not changed.' % (user_id['id'],)
    except urllib2.HTTPError, e:
        error = json.loads(e.read())
        print error['error']['message']

def friend_users(user_1, user_2):
    url = "https://%s/%s/friends/%s"
    try:
        print "User 1 -> User 2...",
        d1 = {'access_token': user_1['access_token']}
        f1 = urllib2.urlopen(url % (GRAPH_URL, user_1['id'], user_2['id']), data=urllib.urlencode(d1))
        f1.read()
        print "done."
    except urllib2.HTTPError, e:
        error = json.loads(e.read())
        print error['error']['message']

    try:
        print "User 2 -> User 1...",
        d2 = {'access_token': user_2['access_token']}
        f2 = urllib2.urlopen(url % (GRAPH_URL, user_2['id'], user_1['id']), data=urllib.urlencode(d2))
        f2.read()
        print "done."
    except urllib2.HTTPError, e:
        error = json.loads(e.read())
        print error['error']['message']

def print_user(user):
    token_str = 'No token.'
    if 'access_token' in user:
        token_str = user['access_token']
    print "User %s:\n    login_url: %s\n    token: %s" % (user['id'], user['login_url'], token_str)
    if 'email' in user:
        print "    email: %s" % user['email']

def print_users(users):
    if len(users) == 0:
        print "No users."
        return
    print 'Users: '
    for user in users:
        print_user(user)

def question(question, options=None, default=None):
    while True:
        prompt = " %s: " % question
        if options is not None:
            prompt = " %s (%s): " % (question, '/'.join(["[%s]" % o if o == default else o for o in options]))
        answer = raw_input(prompt).strip().upper()
        if len(answer) == 0 and default is not None:
            answer = default
        if options is not None:
            if answer in options:
                return answer
        else:
            return answer


def question_user(question):
    while True:
        try:
            user_id = raw_input(" %s ID: " % question)
            user = next(u for u in users if u['id'] == user_id)
            return user
        except (IndexError, ValueError):
            print 'Invalid user ID.'

if __name__ == '__main__':
    usage = "usage: %prog <app_id> <app_secret>"
    parser = OptionParser(usage=usage)
    (options, args) = parser.parse_args()
    app_id = APP_ID
    app_secret = APP_SECRET
    if len(args) > 0:
        app_id = args.get(0)
    if len(args) > 1:
        app_secret = args.get(1)

    print 'Getting access token...',
    access_token = get_access_token(app_id, app_secret)
    print 'done.'

    print 'Loading users...',
    users = load_users(app_id, access_token)
    print 'done.'

    while True:
        try:
            input = raw_input('Command (? for help): ').lower()
            if len(input) != 0:
                cmd = input.strip()
                if cmd == '?':
                    print "Commands:\n  n  New user\n  l  List users\n  p  Set user password\n  r  Reload user list\n  d  Delete user\n  f  Friend users\n  q  Quit"
                elif cmd == 'n':
                    installed = question('App Installed', options=['Y', 'N'], default='N').upper()
                    installed_options = {'Y': 'true', 'N': 'false'}
                    if installed == 'Y':
                        permissions = raw_input(' Permissions (comma separated): ')
                    else:
                        permissions = None
                    print "Creating user..."
                    new_user = create_user(app_id, access_token, installed_options[installed], permissions)
                    print_user(new_user)
                    users = load_users(app_id, access_token)
                    print "User added."
                elif cmd == 'l':
                    print_users(users)
                elif cmd == 'r':
                    print 'Loading users...'
                    users = load_users(app_id, access_token)
                    print "Done"
                elif cmd == 'f':
                    user_1 = question_user('First User')
                    user_2 = question_user('Second User')
                    if 'access_token' not in user_1 or 'access_token' not in user_2:
                        print 'Both users need to have access tokens.'
                    elif user_1 == user_2:
                        print 'Users cannot friend themselves.'
                    else:
                        print "Friending users..."
                        friend_users(user_1, user_2)
                elif cmd == 'd':
                    user = question_user('User')
                    r = delete_user(user['id'], access_token)
                    if r:
                        print 'User deleted.'
                    else:
                        print 'User not deleted.'
                    users = load_users(app_id, access_token)
                elif cmd == 'p':
                    user_1 = question_user('User')
                    password = raw_input("Enter Password: ").strip()
                    modify_result = set_user_password(user_1, password)
                    print modify_result
                elif cmd == 'q' or cmd == 'quit' or cmd == 'exit':
                    sys.exit(1)
                else:
                    print 'Unknown command.'
        except (EOFError, KeyboardInterrupt):
            sys.exit(1)
