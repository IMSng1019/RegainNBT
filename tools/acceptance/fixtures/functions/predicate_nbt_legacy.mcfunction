summon minecraft:zombie 6 -59 7 {equipment:{mainhand:{count:1,id:"minecraft:diamond_sword"}},Tags:["rnbt_pred"]}
execute if entity @e[type=minecraft:zombie,tag=rnbt_pred,nbt={HandItems:[{id:"minecraft:diamond_sword",Count:1b}]}] run data merge block 6 -60 7 {rnbt_match:1b}
