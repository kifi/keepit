## Export from Mixpanel

Advise to only download 1 day at a time. An uncompressed file from 1 day can be 200-350+ MB. The script takes 2 arguments that are the start and end dates in YYYY-MM-DD format, inclusive.

```sh
# example
python mixpanel_export.py 2015-07-01 2015-07-01
```

Each download will save a file of the form  `mixpanel-20150701-20150701.txt` in the `exports` directory.

## Import to Amplitude

Ensure `node` is installed and run `npm install`. This version was tested with v0.12.7

```sh
# example
node amplitude_import.js exports/mixpanel-20150701-20150701.txt
```

`<FILENAME>` should be the name of the file created from `python export.py`

The amplitude_import.js is rate limited to send to amplitude no more than 50 concurrently.
