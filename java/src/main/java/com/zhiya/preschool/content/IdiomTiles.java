package com.zhiya.preschool.content;

import java.util.List;

/** 成语填字使用的基础候选字，供答题和关卡预览共用。 */
public final class IdiomTiles {
    public static final List<Tile> DISTRACTORS = List.of(
        new Tile("彩", "cǎi"), new Tile("义", "yì"), new Tile("颜", "yán"),
        new Tile("兴", "xìng"), new Tile("心", "xīn"), new Tile("高", "gāo"),
        new Tile("六", "liù")
    );

    private IdiomTiles() {
    }

    public record Tile(String character, String pinyin) {
    }
}
