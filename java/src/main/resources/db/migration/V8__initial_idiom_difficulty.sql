-- 先给现有 11 关的词条分级。词条难度独立于题目的挖空和候选字难度。
-- 仅补空值，保留已经人工设置的难度。
UPDATE idiom_entry
SET difficulty = 1
WHERE difficulty IS NULL
  AND term IN ('兴高采烈', '一心一意', '五颜六色', '大吃一惊', '自言自语', '东张西望', '手舞足蹈');

UPDATE idiom_entry
SET difficulty = 2
WHERE difficulty IS NULL
  AND term IN (
      '一目了然', '画龙点睛', '井井有条', '守株待兔', '亡羊补牢', '争先恐后',
      '目瞪口呆', '画蛇添足', '七上八下', '一五一十', '目不转睛', '狐假虎威',
      '对牛弹琴', '勤学苦练', '半途而废', '刻舟求剑', '掩耳盗铃'
  );

UPDATE idiom_entry
SET difficulty = 3
WHERE difficulty IS NULL
  AND term IN ('惊弓之鸟', '专心致志', '水到渠成', '事半功倍', '胸有成竹', '闻鸡起舞');

UPDATE idiom_entry
SET difficulty = 4
WHERE difficulty IS NULL
  AND term IN ('不耻下问', '卧薪尝胆', '负荆请罪');
