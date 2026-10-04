summon minecraft:zombie 6 -59 7 {Tags:["rnbt-pred"],equipment:{mainhand:{id:"minecraft:diamond_sword",count:1}}}
execute if entity @e[tag=rnbt-pred,nbt={HandItems:[{id:"minecraft:diamond_sword",Count:1b}]}] run data modify entity @e[tag=rnbt-pred,limit=1] Tags append value "RNBT-POSITIVE"
execute if entity @e[tag=rnbt-pred,nbt={HandItems:[{id:"minecraft:golden_sword",Count:1b}]}] run data modify entity @e[tag=rnbt-pred,limit=1] Tags append value "RNBT-NEGATIVE"
execute if entity @e[tag=rnbt-pred,nbt={equipment:{mainhand:{id:"minecraft:diamond_sword",count:1}}}] run data modify entity @e[tag=rnbt-pred,limit=1] Tags append value "RNBT-MODERN"
