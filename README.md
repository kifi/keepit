Sub mobules
===========
We use git sub modules to integrate the marketing site. For more info about git submobules see:
https://chrisjean.com/2009/04/20/git-submodules-adding-using-removing-and-updating/

To add the submodule we did:
> git submodule add git@github.com:kifi/FrogSpark kifi-backend/modules/shoebox/marketing

To update the submodule:
> git submodule update
> cd kifi-backend/modules/shoebox/marketing
> git status
> git checkout master
> git pull



