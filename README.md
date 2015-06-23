Sub modules
===========
We use git sub modules to integrate the marketing site. For more info about git submobules see:
https://chrisjean.com/2009/04/20/git-submodules-adding-using-removing-and-updating/

To add the submodule we did:
```
git submodule add git@github.com:kifi/FrogSpark kifi-backend/modules/shoebox/marketing
```

To update the submodule on dev:
```
cd kifi-backend/modules/shoebox/marketing
git pull
cd ..
git add marketing
```
then commit & push the changes

On Jenkins
do a pull for the new code and then
```
git submodule update --init
```
and then build

Gulp
====

- In the `kifi-backend/modules/shoebox/angular` directory, run `npm install`
- `npm install -g gulp`
- Edit `/etc/hosts` with `sudo` adding the following:

    127.0.0.1      dev.ezkeep.com
    108.60.110.146 office
    172.18.0.11    arthur

- `gulp`
- Log in to http://kifi.com
- `http://dev.ezkeep.com:8080/` should now load your local instance of Kifi.
