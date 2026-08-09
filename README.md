## What it comes with:

### _-auto login support_

### _-multiple stasis pulling_

## instructions:

Step 1: install the mod and put it in an ALT's mod folder, along with fabric api

Step 2: Build a stasis chamber and throw your ender pearl in (6 block hole, soul sand at the bottom, open trapdoor at the top, filled with 5 water blocks so it bubbles at the top)

Step 3: Run the instance of your alt, then close it

Step 4: Go to /Minecraft/config/trapdoor-pulse-client.json

Step 5: open the file, and replace "insert username here" with your username

Step 6: Replace "insert trapdoor type here" with your trapdoor's wood type (wood types: oak, spruce, birch, jungle, acacia, dark_oak, mangrove, cherry, bamboo, crimson, warped)

Step 7: if your ALT's account is cracked, replace crackedpass with thier password, if not, leave it blank

Step 8: open your ALT's instance, and go WITHIN 4 blocks of the stasis you set up earlier

Step 9: now from ANYWHERE in the world, on your main instance, do "/w (ALT's name) p" and you will be teleported back to your stasis, then simply put another pearl in your stasis, and its ready again!

(optional) Step 10: install Wurst client and use AutoReconnect to reconnect after the server restart

## Note:

You are able to add multiple players following the same steps, but they must have a different trapdoor wood type. here is an example json file:


```
{
  "crackedpass": "",
  "stations": [
    {
      "player": "insert username here",
      "trapdoor": "oak"
    },
      "player2": "insert username here",
      "trapdoor": "bamboo"
    }
  ]
}
```


## how it works:

your ALT looks for when your main whispers to it, "p" in the console.

that triggers code that looks for the nearest opened trapdoor of the type specified in the config, then briefly closes and opens that trapdoor

it also takes the cracked password from the alts config and puts it in front of /login when you join
What you can do with this:

Since i used the ARR (all rights reserved) licence, it gives me all rights unless explitly stated, you MAY use this on any client you like, or take insperation from this to make another mod to work with your own server.

you may NOT try to parse the data in any way, or abuse it in an unjustified way, and please ask me on 10b10t if you have any questions about use. thanks! -Muck_Foyang
