#!/bin/bash
lessc styles/keeper/tagbox.less styles/keeper/tagbox.css
lessc styles/keeper/pane.less styles/keeper/pane.css
lessc styles/keeper/message_participants.less styles/keeper/message_participants.css
lessc styles/keeper/message_mute.less styles/keeper/message_mute.css
$(dirname $0)/build.sh
