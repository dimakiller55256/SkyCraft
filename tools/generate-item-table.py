"""Generate the reviewed default table from verified local EditorID/FormID metadata."""
import json, re
from pathlib import Path

root=Path(__file__).resolve().parents[1]
records=json.loads((root/'.tools/item-records.json').read_text(encoding='utf-8-sig'))
exact={
 'OreIron':'raw_iron','OreGold':'raw_gold','OreSilver':'raw_iron','OreCorundum':'raw_copper',
 'OreOrichalcum':'raw_iron','OreEbony':'coal','OreMoonstone':'quartz','OreMalachite':'emerald','OreQuicksilver':'raw_iron',
 'IngotIron':'iron_ingot','IngotSteel':'iron_ingot','IngotGold':'gold_ingot','ingotSilver':'iron_ingot',
 'IngotCorundum':'copper_ingot','IngotOrichalcum':'iron_ingot','IngotDwarven':'gold_ingot','IngotEbony':'netherite_scrap',
 'IngotIMoonstone':'quartz','IngotMalachite':'emerald','IngotQuicksilver':'iron_ingot',
 'Gold001':'gold_nugget','Lockpick':'iron_nugget','Leather01':'leather','LeatherStrips':'string',
 'Firewood01':'oak_log','Coal01':'coal','Charcoal':'charcoal','PaperRoll':'paper','Quill01':'feather',
 'Inkwell01':'ink_sac','Lantern01':'lantern','Bucket01':'bucket','Bucket02a':'bucket','Bucket02b':'bucket',
 'MammothTusk':'bone','HorkerTusk':'bone','DragonBone':'bone','DragonScales':'turtle_scute','ChaurusChitin':'turtle_scute',
 'RuinedBook':'book','RuinedBook02':'book','BurnedBook01':'paper','BoneHumanSkullFull':'skeleton_skull',
 'Wheat':'wheat','BoneMeal':'bone_meal','HawkFeathers':'feather','HagravenFeathers':'feather',
 'BeeHoneyComb':'honeycomb','BeeHiveHusk':'honeycomb','MoonSugar':'sugar','TundraCotton':'white_wool',
 'SpiderEgg':'spider_eye','ChaurusEggs':'spider_eye','BirdEgg01':'egg','BirdEgg02':'egg','BirdEgg03':'egg',
 'glowDust':'glowstone_dust','vampireDust':'gunpowder','FireSalts':'blaze_powder','FrostSalts':'snowball',
 'VoidSalts':'ender_pearl','Ectoplasm':'slime_ball','SaltPile':'sugar','Snowberry':'sweet_berries',
 'JuniperBerries':'sweet_berries','JazBay':'sweet_berries','Pearl':'ender_pearl','PearlSmall':'ender_pearl',
 'FoodHoney':'honey_bottle','FoodApple':'apple','FoodApple02':'apple','FoodCarrot':'carrot',
 'FoodPotato':'potato','FoodPotatoesBaked':'baked_potato','FoodGourd':'pumpkin',
 'FoodChicken':'chicken','FoodChickenCooked':'cooked_chicken','FoodRabbit':'rabbit','FoodRabbitCooked':'cooked_rabbit',
 'FoodSalmon':'salmon','FoodSalmonCooked':'cooked_salmon','FoodSlaughterfishCooked':'cooked_cod',
 'FoodPie':'pumpkin_pie','FoodSweetroll':'bread','FoodBoiledCremeTreat':'cookie','FoodLongTaffyTreat':'cookie',
 'FoodHoneyNutTreat':'cookie','FoodBread01A':'bread','FoodBread01B':'bread',
 'Pickaxe01':'iron_pickaxe','WoodcuttersAxe01':'iron_axe','Shovel01':'iron_shovel','Shovel02':'iron_shovel',
 'Pitchfork001':'iron_hoe','Pitchfork002':'iron_hoe','Broom01':'stick',
}
exact={k.lower():v for k,v in exact.items()}

def mapped(r):
 e=r['editor'];s=e.lower();t=r['type']
 if r['ench']:return None
 if s in exact:return exact[s],'ресурсы / бытовые предметы / еда'
 if t=='MISC':
  if re.fullmatch(r'gem(diamond|emerald|amethyst|ruby|garnet|sapphire)(flawless)?',s):
   gem=re.search(r'gem(.*?)(?:flawless)?$',s)[1]
   return {'diamond':'diamond','emerald':'emerald','amethyst':'amethyst_shard','ruby':'redstone','garnet':'redstone','sapphire':'lapis_lazuli'}[gem],'драгоценности'
  if re.fullmatch(r'(bear(snow|cave)?|fox(snow)?|wolf(ice)?|sabrecat(snow)?|werewolf|deer)pelt|(?:deer(?:02)?|horse|cow|goat)hide',s):return 'leather','шкуры'
  if s.startswith('dwarven') and any(x in s for x in ('scrap','platemetal','cog','gear','gyro','dynamo')):return 'gold_nugget','двемерский металл'
  if re.fullmatch(r'(basicwoodenbowl01|glazed(?:bowl|cup|goblet|jug|pot|plate).*|silver(?:bowl|plate|platter|jug|goblet).*|castironpot.*|kettle01)',s):return 'bowl','посуда'
  if re.fullmatch(r'(?:winebottle.*|winesolitudespicedbottle)empty',s):return 'glass_bottle','пустые бутылки'
  if s.startswith('byohmaterial'):
   for a,b in [('nail','iron_nugget'),('hinge','iron_nugget'),('fitting','iron_ingot'),('lock','iron_ingot'),('clay','clay_ball'),('stone','cobblestone'),('glass','glass'),('straw','wheat')]:
    if a in s:return b,'строительные материалы Hearthfire'
 if t=='INGR':
  if s.startswith('critterpondfish'):return 'cod','рыба'
  if re.fullmatch(r'mushroom0[1-6]|moratapinellabits|scalypholiotabits|glowingmushroom',s):return 'red_mushroom' if s in ('mushroom01','mushroom02','glowingmushroom') else 'brown_mushroom','грибы'
  if s.startswith('mountainflower'):return 'poppy' if 'red' in s else 'cornflower' if 'blue' in s else 'allium','цветы'
 if t=='ALCH':
  if s.startswith('food'):
   if any(x in s for x in ('soup','stew','fondue')):return 'rabbit_stew' if 'rabbit' in s else 'mushroom_stew','еда: супы'
   if 'cheese' in s:return 'bread','еда: сыр → хлеб (аналога нет)'
   if any(x in s for x in ('venison','mammothmeat','horsemeat','horkermeat','beef')):return 'cooked_beef' if 'cooked' in s else 'beef','еда: мясо'
   if 'goatmeat' in s:return 'cooked_mutton' if 'cooked' in s else 'mutton','еда: козлятина'
   if 'pheasant' in s:return 'cooked_chicken' if 'cooked' in s else 'chicken','еда: фазан'
   if any(x in s for x in ('leek','cabbage','tomato')):return 'carrot','еда: овощи → морковь'
   if any(x in s for x in ('wine','mead')):return 'honey_bottle','напитки → бутылочка мёда'
  if s=='ale':return 'honey_bottle','напитки'
  for prefix,potion in [('restorehealth','healing'),('fortifyhealrate','regeneration'),('resistfire','fire_resistance'),('waterbreathing','water_breathing'),('invisibility','invisibility'),('damagehealth','poison')]:
   if re.fullmatch(prefix+r'\d+',s):return ('splash_potion' if prefix=='damagehealth' else 'potion')+'#'+potion,'зелья (приближённый эффект Minecraft)'
 if t=='AMMO' and re.fullmatch(r'(?:dlc[12])?(?:iron|steel|dwarven|elven|orcish|glass|ebony|daedric|dragonbone|draugr|forsworn|falmer|nordic|stalhrim)(?:arrow|bolt).*',s):return 'arrow','стрелы / болты'
 if t=='WEAP' and re.fullmatch(r'(?:dlc[12])?(?:iron|steel|silver|dwarven|elven|orcish|glass|ebony|daedric|dragonbone|draugr|forsworn|falmer|nordic|stalhrim)(?:sword|greatsword|dagger|waraxe|battleaxe|mace|warhammer|bow|crossbow)(?:0[1-9])?',s):
  if 'crossbow' in s:return 'crossbow','оружие'
  if 'bow' in s:return 'bow','оружие'
  material='netherite' if any(x in s for x in ('daedric','dragonbone','ebony')) else 'diamond' if any(x in s for x in ('glass','stalhrim')) else 'golden' if 'dwarven' in s else 'iron'
  return material+('_axe' if any(x in s for x in ('axe','mace','hammer')) else '_sword'),'оружие (размер и сила приближены)'
 if t=='ARMO' and re.fullmatch(r'(?:dlc[12])?armor(?:iron|bandediron|steel|steelplate|imperial|leather|hide|scaled|studded|dwarven|elven|elvengilded|orcish|glass|ebony|daedric|dragonplate|dragonscale|nordic|stalhrim)(?:helmet|cuirass|gauntlets|boots|shield)(?:0[1-9])?',s):
  if 'shield' in s:return 'shield','щиты'
  material='leather' if any(x in s for x in ('leather','hide','studded')) else 'netherite' if any(x in s for x in ('daedric','dragon','ebony')) else 'diamond' if any(x in s for x in ('glass','stalhrim')) else 'golden' if 'dwarven' in s else 'iron'
  piece='helmet' if 'helmet' in s else 'chestplate' if 'cuirass' in s else 'boots' if 'boots' in s else 'leggings'
  return material+'_'+piece,'броня (перчатки → поножи)'
 if t=='ARMO' and s.startswith('jewelry') and re.fullmatch(r'jewelry(?:ring|necklace)(?:gold|silver)(?:gems|diamond|emerald|sapphire|ruby|garnet|amethyst)?',s):
  for gem,item in [('diamond','diamond'),('emerald','emerald'),('sapphire','lapis_lazuli'),('ruby','redstone'),('garnet','redstone'),('amethyst','amethyst_shard')]:
   if gem in s:return item,'украшения → соответствующий камень'
  return 'gold_nugget' if 'gold' in s else 'iron_nugget','украшения → самородок'
 if t=='ARMO' and re.fullmatch(r'clothes(?:farm|fine|beggar|miner|merchant|barkeeper|blacksmith|wench|chef|prisoner|monk|college|robesblue|robesblack|redguard|warlock|necromancer|mourner)[a-z0-9_]*',s):
  piece='boots' if any(x in s for x in ('boots','shoes','sandals')) else 'helmet' if any(x in s for x in ('hat','hood')) else 'leggings' if any(x in s for x in ('gloves','gauntlets')) else 'chestplate'
  return 'leather_'+piece,'одежда → кожаная броня'
 if t=='SLGM' and re.fullmatch(r'soulgem(?:petty|lesser|common|greater|grand|black)(?:filled)?',s):return 'experience_bottle' if 'filled' in s else 'quartz','камни душ (сила души не переносится)'
 if t=='LIGH' and s=='torch01':return 'torch','факелы'
 if t=='BOOK' and re.fullmatch(r'book[0-9]+(?:[a-z])?',s):return 'book','обычные книги → пустая книга'
 return None

rows=[]
for r in records:
 m=mapped(r)
 if m:rows.append(dict(plugin=r['plugin'],form=r['form'],editor=r['editor'],minecraft='minecraft:'+m[0],count=1,category=m[1]))
table=dict(schema=1,enabled=True,description='Автоматически конвертируются только новые поступления; неизвестные, квестовые, краденые, зачарованные, отравленные, улучшенные и надетые предметы остаются в Skyrim. 1 к 1; замену можно изменить.',items=rows)
(root/'config').mkdir(exist_ok=True)
payload=json.dumps(table,ensure_ascii=False,indent=2)+'\n'
(root/'config/item-conversion.json').write_text(payload,encoding='utf-8')
(root/'skse/src/ItemDefaults.h').write_text('#pragma once\n#include <string_view>\nnamespace skycraft { inline constexpr std::string_view kDefaultItemTableParts[] = {\n'+',\n'.join('R"SKYITEMS('+payload[i:i+4000]+')SKYITEMS"' for i in range(0,len(payload),4000))+'\n}; }\n',encoding='utf-8')
print(f'{len(rows)} verified mappings')
