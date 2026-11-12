package com.rc.novelreader.sniff;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FillerFilterTest {
    private static String story(int n) {
        return story(n, "河岸");
    }

    private static String story(int n, String place) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append('\n');
            sb.append("夜色如墨。他沿着" + place + "走了很久。第").append(i).append("盏灯在远处亮起。风里有潮湿的气味。");
        }
        return sb.toString();
    }

    @Test
    public void notesAndBeggingAtTheEndAreRemoved() {
        String text = story(12) + "\n（本章完）\nPS：今天两更，求月票求推荐票！\n感谢书友青山的打赏，加更明天补上。";
        assertEquals(story(12), FillerFilter.strip(text));
    }

    @Test
    public void authorNoteRunsToTheEnd() {
        String text = story(14) + "\n作者的话：\n这一章写了很久。\n主角的身世后面会交代。";
        assertEquals(story(14), FillerFilter.strip(text));
    }

    @Test
    public void noteAtTheStartIsRemoved() {
        String text = "ps: 新书求收藏，拜谢各位。\n" + story(12);
        assertEquals(story(12), FillerFilter.strip(text));
    }

    /** A vote mentioned inside the story, or a long last paragraph, is not filler. */
    @Test
    public void storyTextIsKept() {
        String middle = story(6) + "\n他把那张月票夹进了车窗的缝里。\n" + story(6);
        assertEquals(middle, FillerFilter.strip(middle));
        String tail = story(10) + "\n“订阅的报纸今天没有送来。”她说完这句话就挂了电话，屋子里只剩下钟摆的声音，一下，又一下，"
                + "像是在数着什么。他站了很久，直到窗外的天色一点一点暗下去，才想起来自己连灯都没有开。";
        assertEquals(tail, FillerFilter.strip(tail));
    }

    /** A line that closes several chapters of one book is the site's or the author's boilerplate. */
    @Test
    public void lineRepeatedAcrossChaptersIsLearned() {
        FillerFilter f = new FillerFilter();
        String slogan = "灯下书屋与你一起守候每一个故事";
        String first = f.strip(1, story(10, "城墙") + "\n" + slogan);
        assertTrue(first.endsWith(slogan));
        f.strip(2, story(11, "山路") + "\n" + slogan);
        String third = f.strip(3, story(12) + "\n" + slogan);
        assertFalse(third.contains(slogan));
        assertEquals(story(12), third);
    }

    @Test
    public void englishNotes() {
        String body = "The rain had stopped by the time she reached the harbour. Nobody was waiting.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(body).append(' ').append(i).append('\n');
        String text = sb + "Author's note: thanks for reading!\nSupport me on Patreon for advance chapters.";
        assertEquals(sb.toString().trim(), FillerFilter.strip(text));
    }
}
