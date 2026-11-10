import { API_BASE } from "../../config/api";

const LEARNER_STORAGE_KEY = "zhiya.localLearnerId";
const DRAFT_STORAGE_KEY = "zhiya.idiomDraft";
let learnerRegistration: Promise<void> | undefined;

type Question = { id: number; promptText: string; promptPinyin: string };
type Tile = {
  tileNo: number;
  characterText: string;
  pinyin: string;
  used?: boolean;
};
type Session = {
  sessionId: number;
  nextLevelNo?: number | null;
  title: string;
  questions: Question[];
  tiles: Tile[];
};
type CurrentGame =
  | { status: "PLAYING"; levelNo: number; session: Session }
  | { status: "LOCKED"; levelNo: number; freeLevelCount: number }
  | { status: "COMPLETED" };
type Draft = {
  learnerId: string;
  sessionId: number;
  answers: Record<number, number>;
};
type Result = {
  correctCount: number;
  total: number;
  results: Array<{
    questionId: number;
    correct: boolean;
    sourceText: string;
    fullPinyin: string;
    meaning: string;
  }>;
};

function request<T>(
  url: string,
  method: "GET" | "POST",
  data?: object,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const learnerId = wx.getStorageSync(LEARNER_STORAGE_KEY);
    wx.request({
      url: API_BASE + url,
      method,
      data,
      header: learnerId ? { "X-Local-Learner-Id": learnerId } : {},
      success(response) {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data as T);
        } else {
          reject(
            new Error(
              (response.data as { message?: string })?.message || "请求失败",
            ),
          );
        }
      },
      fail(error) {
        reject(error);
      },
    });
  });
}

async function ensureLocalLearner() {
  if (wx.getStorageSync(LEARNER_STORAGE_KEY)) {
    return;
  }

  learnerRegistration ??= request<{ learnerId: string }>("/learners", "POST")
    .then(({ learnerId }) => {
      wx.setStorageSync(LEARNER_STORAGE_KEY, learnerId);
    })
    .finally(() => {
      learnerRegistration = undefined;
    });
  await learnerRegistration;
}

Page({
  data: {
    loading: true,
    submitting: false,
    error: "",
    locked: false,
    completed: false,
    freeLevelCount: 0,
    sessionId: 0,
    levelNo: 1,
    nextLevelNo: null as number | null,
    title: "第一关",
    questions: [] as Array<
      Question & {
        prefix: string;
        suffix: string;
        selectedText: string;
        selectedTileNo: number;
        active: boolean;
      }
    >,
    tiles: [] as Tile[],
    activeQuestionId: 0,
    result: null as Result | null,
  },
  onLoad() {
    this.start();
  },
  start() {
    return this.loadGame(false);
  },
  replayFirstLevel() {
    return this.loadGame(true);
  },
  async loadGame(replay: boolean) {
    this.setData({
      loading: true,
      error: "",
      locked: false,
      completed: false,
      result: null,
      nextLevelNo: null,
      activeQuestionId: 0,
    });
    try {
      await ensureLocalLearner();
      const current: CurrentGame = replay
        ? {
            status: "PLAYING",
            levelNo: 1,
            session: await request<Session>("/levels/1/sessions", "POST"),
          }
        : await request<CurrentGame>("/sessions/current", "POST");
      if (current.status === "COMPLETED") {
        wx.setNavigationBarTitle({ title: "成语填字" });
        this.setData({ loading: false, completed: true });
        return;
      }

      wx.setNavigationBarTitle({
        title: `成语填字 · 第 ${current.levelNo} 关`,
      });
      if (current.status === "LOCKED") {
        this.setData({
          loading: false,
          locked: true,
          levelNo: current.levelNo,
          freeLevelCount: current.freeLevelCount,
        });
        return;
      }

      const { session, levelNo } = current;
      const draft = wx.getStorageSync(DRAFT_STORAGE_KEY) as Draft | undefined;
      const savedAnswers =
        draft?.sessionId === session.sessionId &&
        draft.learnerId === wx.getStorageSync(LEARNER_STORAGE_KEY)
          ? draft.answers
          : {};
      const usedTiles = new Set<number>();
      const questions = session.questions.map((q) => {
        const parts = q.promptText.split("□");
        const tile = session.tiles.find(
          (candidate) =>
            candidate.tileNo === savedAnswers[q.id] &&
            !usedTiles.has(candidate.tileNo),
        );
        if (tile) {
          usedTiles.add(tile.tileNo);
        }
        return {
          ...q,
          prefix: parts[0],
          suffix: parts[1] || "",
          selectedText: tile?.characterText ?? "",
          selectedTileNo: tile?.tileNo ?? 0,
        };
      });
      const activeQuestionId =
        questions.find((q) => !q.selectedTileNo)?.id ?? questions[0]?.id ?? 0;
      this.setData({
        loading: false,
        levelNo,
        sessionId: session.sessionId,
        nextLevelNo: replay ? null : (session.nextLevelNo ?? null),
        title: session.title,
        questions: questions.map((q) => ({
          ...q,
          active: q.id === activeQuestionId,
        })),
        tiles: session.tiles.map((tile) => ({
          ...tile,
          used: usedTiles.has(tile.tileNo),
        })),
        activeQuestionId,
      });
    } catch (error) {
      this.setData({ loading: false, error: (error as Error).message });
    }
  },
  saveDraft() {
    const answers: Record<number, number> = {};
    for (const question of this.data.questions) {
      if (question.selectedTileNo) {
        answers[question.id] = question.selectedTileNo;
      }
    }
    wx.setStorageSync(DRAFT_STORAGE_KEY, {
      learnerId: wx.getStorageSync(LEARNER_STORAGE_KEY),
      sessionId: this.data.sessionId,
      answers,
    });
  },
  selectQuestion(event: WechatMiniprogram.CustomEvent) {
    if (this.data.result || this.data.submitting) {
      return;
    }
    const id = Number(event.currentTarget.dataset.id);
    const questions = this.data.questions.map((q) => ({
      ...q,
      active: q.id === id,
    }));
    this.setData({ activeQuestionId: id, questions });
  },
  selectTile(event: WechatMiniprogram.CustomEvent) {
    if (this.data.result || this.data.submitting) {
      return;
    }
    const tileNo = Number(event.currentTarget.dataset.no);
    const tile = this.data.tiles.find((t) => t.tileNo === tileNo);
    if (!tile || tile.used) {
      return;
    }
    const id = this.data.activeQuestionId;
    const questions = this.data.questions.map((q) =>
      q.id === id
        ? { ...q, selectedText: tile.characterText, selectedTileNo: tileNo }
        : q,
    );
    const old = this.data.questions.find((q) => q.id === id);
    const tiles = this.data.tiles.map((t) => ({
      ...t,
      used:
        t.tileNo === tileNo
          ? true
          : t.tileNo === old?.selectedTileNo
            ? false
            : !!t.used,
    }));
    const next = questions.find((q) => !q.selectedText);
    this.setData({
      questions: questions.map((q) => ({
        ...q,
        active: q.id === (next?.id || id),
      })),
      tiles,
      activeQuestionId: next?.id || id,
    });
    this.saveDraft();
  },
  clearQuestion(event: WechatMiniprogram.CustomEvent) {
    if (this.data.result || this.data.submitting) {
      return;
    }
    const id = Number(event.currentTarget.dataset.id);
    const old = this.data.questions.find((q) => q.id === id);
    const questions = this.data.questions.map((q) =>
      q.id === id
        ? { ...q, selectedText: "", selectedTileNo: 0, active: true }
        : { ...q, active: false },
    );
    const tiles = this.data.tiles.map((t) => ({
      ...t,
      used: t.tileNo === old?.selectedTileNo ? false : !!t.used,
    }));
    this.setData({ questions, tiles, activeQuestionId: id });
    this.saveDraft();
  },
  async submit() {
    if (this.data.submitting || this.data.result) {
      return;
    }
    if (this.data.questions.some((q) => !q.selectedText)) {
      wx.showToast({ title: "还有空位没填", icon: "none" });
      return;
    }
    this.setData({ submitting: true });
    try {
      const answers = this.data.questions.map((q) => ({
        questionId: q.id,
        tileNo: q.selectedTileNo,
      }));
      const result = await request<Result>(
        `/sessions/${this.data.sessionId}/submit`,
        "POST",
        { answers },
      );
      this.setData({ result });
      wx.removeStorageSync(DRAFT_STORAGE_KEY);
    } catch (error) {
      wx.showToast({ title: (error as Error).message, icon: "none" });
    } finally {
      this.setData({ submitting: false });
    }
  },
  nextLevel() {
    if (!this.data.result) {
      return;
    }

    if (this.data.nextLevelNo === null) {
      this.returnHome();
      return;
    }
    this.start();
  },
  returnHome() {
    wx.navigateBack();
  },
});
