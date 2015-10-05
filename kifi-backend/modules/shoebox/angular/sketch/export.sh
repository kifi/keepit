#!/bin/bash
sketchtool export artboards --output="../img/svg/" --save-for-web=YES --compact=YES ./svgs.sketch
sketchtool export artboards --output="../img/symbol-sprites/lib" --save-for-web=YES --compact=YES ./symbol-sprites.sketch
