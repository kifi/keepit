Sub mobules
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


