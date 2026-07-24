/* ═══════════════════════════════════════════════════════════════════════
   Naada (నాద) — app.js  ·  Production music player
═══════════════════════════════════════════════════════════════════════ */
const APP_VERSION = "v30";

/* ── Lightweight diagnostics ─────────────────────────────────────────────
   ndlog() is a no-op stub (kept so the inline trace calls are harmless).
   Real JS errors still surface in the browser console for future debugging. */
window.ndlog = function(){};
window.addEventListener("error", e => {
  console.error("[naada] JS ERROR:", e.message, "@", (e.filename||"").split("/").pop()+":"+e.lineno);
});
window.addEventListener("unhandledrejection", e => {
  console.error("[naada] PROMISE REJECT:", e.reason?.message || e.reason);
});

/* ── Storage helpers ────────────────────────────────────────────────────── */
const LS = {
  get: (k, d) => { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } },
  set: (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
};

/* ── State ──────────────────────────────────────────────────────────────── */
const S = {
  page:       "home",
  homeLang:   "hindi",
  sType:      "songs",
  queue:      [],
  queueIdx:   -1,
  track:      null,       // now playing
  playing:    false,
  loading:    false,
  shuffle:    LS.get("nd_shuffle", false),
  repeat:     LS.get("nd_repeat",  false),  // false | "all" | "one"
  liked:      LS.get("nd_liked",   []),
  playlists:  LS.get("nd_pls",     []),     // [{id,name,songs:[],pinned?}]
  history:    LS.get("nd_history", []),
  pinned:     LS.get("nd_pinned",  []),     // pinned songs
  theme:      LS.get("nd_theme",   "auto"), // "auto" | "light" | "dark"
  sleepTimer: null,
  sleepEnd:   null,
  addPlTrack: null,       // track pending add-to-playlist
  viewPlId:   null,       // playlist open in detail sheet
  searchQ:    "",
};

const audio = new Audio();
audio.preload = "none";

/* ── Persist helpers ────────────────────────────────────────────────────── */
const save = () => {
  LS.set("nd_liked",   S.liked);
  LS.set("nd_pls",     S.playlists);
  LS.set("nd_history", S.history);
  LS.set("nd_shuffle", S.shuffle);
  LS.set("nd_repeat",  S.repeat);
  LS.set("nd_pinned",  S.pinned);
};

/* ── Theme (light / auto / dark) ────────────────────────────────────────── */
const THEME_COLORS = { light: "#4A1A6B", dark: "#120C1A" };
const _darkMQ = matchMedia("(prefers-color-scheme: dark)");

function applyTheme() {
  const resolved = S.theme === "dark" || (S.theme === "auto" && _darkMQ.matches)
    ? "dark" : "light";
  document.documentElement.dataset.theme = resolved;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.content = THEME_COLORS[resolved];
}
function setTheme(t) {
  S.theme = t;
  LS.set("nd_theme", t);
  applyTheme();
}
// While on "auto", follow live OS scheme changes (sunset auto-switch etc.)
_darkMQ.addEventListener?.("change", () => { if (S.theme === "auto") applyTheme(); });
applyTheme();

/* ── DOM ────────────────────────────────────────────────────────────────── */
const $  = id  => document.getElementById(id);
const $$ = sel => document.querySelectorAll(sel);

/* ── Utility ────────────────────────────────────────────────────────────── */
function fmt(s) {
  if (!s || isNaN(s)) return "--:--";
  s = Math.floor(s);
  const two = n => String(n).padStart(2, "0");
  // Podcasts routinely run past an hour. Without this a 1h52m episode
  // rendered as "112:10", which reads as nonsense.
  if (s >= 3600) {
    return `${Math.floor(s/3600)}:${two(Math.floor(s%3600/60))}:${two(s%60)}`;
  }
  return `${Math.floor(s/60)}:${two(s%60)}`;
}
function esc(s) {
  return String(s||"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;");
}
function isLiked(id) { return S.liked.some(t => t.id === id); }
function uid() { return Date.now().toString(36) + Math.random().toString(36).slice(2,6); }

let toastT;
function toast(msg, dur=2200) {
  const el = $("toast");
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(toastT);
  toastT = setTimeout(() => el.classList.remove("show"), dur);
}

function greeting() {
  const h = new Date().getHours();
  if (h < 12) return "Good morning";
  if (h < 17) return "Good afternoon";
  if (h < 21) return "Good evening";
  return "Good night";
}

/* ── Navigation ─────────────────────────────────────────────────────────── */
function goto(page) {
  const from = S.page;
  S.page = page;
  // Feed the Android back stack unless we're replaying it
  if (!window._ndSilentNav && typeof window.ndNavPage === "function") {
    window.ndNavPage(from, page);
  }
  $$(".page").forEach(el => el.classList.remove("active"));
  $$(".nav-item").forEach(el => el.classList.toggle("active", el.dataset.page === page));
  $(`page-${page}`).classList.add("active");
  if (page === "library") renderLibrary();
  if (page === "queue")   renderQueueEditable();
  if (page === "trivia")  loadTrivia();
  if (page === "podcasts" && typeof renderPodSubs === "function") renderPodSubs();
  // The language pill only applies to Home content — hide it elsewhere
  const pill = $("lang-pill");
  if (pill) pill.style.display = page === "home" ? "" : "none";
  // Close the language popover on any tab switch
  const pop = $("lang-pop");
  if (pop) pop.style.display = "none";
}

$$(".nav-item").forEach(el => el.addEventListener("click", () => goto(el.dataset.page)));

/* ── Track HTML ─────────────────────────────────────────────────────────── */
function trackHTML(t, idx, numbered=true) {
  const now    = S.track?.id === t.id;
  const liked  = isLiked(t.id);
  const numEl  = numbered
    ? (now && S.playing
        ? `<div class="bars"><span></span><span></span><span></span></div>`
        : `<span class="t-num">${idx+1}</span>`)
    : "";
  return `<div class="track${now?" now":""}" role="listitem" data-idx="${idx}" data-id="${esc(t.id)}">
    ${numEl}
    <div class="t-img">
      ${t.image
        ? `<img src="${esc(t.image)}" alt="" loading="lazy" onerror="this.style.display='none'">`
        : `<span class="t-img-ph">🎵</span>`}
    </div>
    <div class="t-info">
      <div class="t-title">${esc(t.title)}</div>
      <div class="t-meta">${esc(t.artist||"")}${t.album?" · "+esc(t.album):""}</div>
    </div>
    <div class="t-right">
      ${t.duration ? `<span class="t-dur">${fmt(t.duration)}</span>` : ""}
      <button class="t-like${liked?" liked":""}" data-id="${esc(t.id)}" aria-label="${liked?"Unlike":"Like"}">
        <i class="ti ti-heart${liked?"-filled":""}"></i>
      </button>
      <button class="t-menu" data-menu="${esc(t.id)}" aria-label="More options">
        <i class="ti ti-dots-vertical"></i>
      </button>
    </div>
  </div>`;
}

function cardHTML(item, type="album") {
  const key = type === "album" ? "data-albumid" : "data-plid";
  return `<div class="card" ${key}="${esc(item.id)}" role="listitem" tabindex="0" aria-label="${esc(item.title)}">
    <div class="card-img">
      ${item.image
        ? `<img src="${esc(item.image)}" alt="${esc(item.title)}" loading="lazy">`
        : `<div class="card-img-ph">🎵</div>`}
    </div>
    <div class="card-title">${esc(item.title)}</div>
    <div class="card-sub">${esc(item.artist||item.year||"")}</div>
  </div>`;
}

/* ── Bind track list ────────────────────────────────────────────────────── */
// Each container stores its CURRENT track list here. bind() replaces the
// list rather than stacking new click listeners (the old approach added a
// fresh listener on every re-render, so a reused container — e.g. the home
// list shown for both charts and albums — ended up firing stale closures
// and playing the wrong song).
const _listRegistry = new WeakMap();

function bind(container, tracks) {
  // Always update the registry to the latest list for this container
  _listRegistry.set(container, tracks);

  // Attach the click handler only once per container
  if (container._naadaBound) return;
  container._naadaBound = true;

  container.addEventListener("click", e => {
    const current = _listRegistry.get(container) || [];
    window.ndlog && window.ndlog(`tap in ${container.id||"?"} — list has ${current.length} tracks`);
    const like = e.target.closest(".t-like");
    if (like) {
      e.stopPropagation();
      toggleLike(like.dataset.id, current);
      return;
    }
    const row = e.target.closest(".track[data-id]");
    if (!row) { window.ndlog && window.ndlog("  no .track[data-id] matched target"); return; }
    // Resolve by song id first (robust), fall back to index
    const id  = row.dataset.id;
    let track = current.find(t => t.id === id);
    let idx   = current.findIndex(t => t.id === id);
    if (!track) {
      const i = parseInt(row.dataset.idx);
      if (!isNaN(i) && current[i]) { track = current[i]; idx = i; }
    }
    window.ndlog && window.ndlog(`  resolved id=${id} → ${track ? track.title : "NOT FOUND"}`);
    if (track) play(track, current, idx);
  });
}

/* ── Home ───────────────────────────────────────────────────────────────── */
const LANG_LABELS = { hindi:"Hindi", telugu:"Telugu", tamil:"Tamil", english:"English" };
const LANG_GLYPHS = { hindi:"अ", telugu:"త", tamil:"த", english:"A" };

function setLanguage(lang) {
  S.homeLang = lang;
  const lbl = $("lang-pill-label");
  if (lbl) lbl.textContent = LANG_LABELS[lang] || lang;   // optional (compact pill omits it)
  const gly = $("lang-pill-glyph");
  if (gly) gly.textContent = LANG_GLYPHS[lang] || "త";
  // Persist as the default so it sticks across sessions
  S.homeCfg.defaultLang = lang;
  LS.set("nd_homecfg", S.homeCfg);
  // Highlight the active option in the popover
  $$(".lang-opt").forEach(o => o.classList.toggle("active", o.dataset.lang === lang));
  loadCharts(lang);
  loadNewReleases(lang);
  loadHomeShelves(lang);
}

function initHome() {
  $("greeting").textContent = greeting();
  const pill = $("lang-pill"), pop = $("lang-pop");
  if (pill && pop) {
    pill.addEventListener("click", e => {
      e.stopPropagation();
      const open = pop.style.display !== "none";
      pop.style.display = open ? "none" : "block";
      pill.setAttribute("aria-expanded", open ? "false" : "true");
      pill.classList.toggle("open", !open);
    });
    $$(".lang-opt").forEach(opt => {
      opt.addEventListener("click", () => {
        setLanguage(opt.dataset.lang);
        pop.style.display = "none";
        pill.setAttribute("aria-expanded", "false");
        pill.classList.remove("open");
      });
    });
    // Close the popover when tapping elsewhere
    document.addEventListener("click", e => {
      if (pop.style.display !== "none" && !pop.contains(e.target) && e.target !== pill) {
        pop.style.display = "none";
        pill.setAttribute("aria-expanded", "false");
        pill.classList.remove("open");
      }
    });
  }
  // Initial content load is triggered by the bootstrap() IIFE using the
  // saved default language.
}

const CHART_LABELS = { hindi:"Hindi Hits", telugu:"Telugu Chartbusters", tamil:"Tamil Top 40", english:"Global Hits" };

async function loadCharts(lang) {
  // "Trending Now" shelf — uses the real trending feed (distinct from Chartbusters)
  const list  = $("chart-list");
  const title = $("chart-title");
  if (title) title.textContent = "Trending Now";
  list.innerHTML = `<div class="state-msg"><div class="spinner"></div><p>Loading…</p></div>`;
  try {
    const r = await fetch(`/api/trending/${lang}`);
    const d = await r.json();
    if (!d.songs?.length) throw new Error("empty");
    list.innerHTML = d.songs.map((t,i) => trackHTML(t,i,true)).join("");
    bind(list, d.songs);
  } catch {
    list.innerHTML = `<div class="state-msg"><i class="ti ti-wifi-off"></i><p>Couldn't load trending.<br>Check your connection.</p></div>`;
  }
  loadChartbusters(lang);
}

async function loadChartbusters(lang) {
  // "Chartbusters" shelf — JioSaavn's India Superhits Top 50 chart
  const list = $("chartbusters-list");
  if (!list) return;
  list.innerHTML = `<div class="state-msg"><div class="spinner"></div><p>Loading…</p></div>`;
  try {
    const r = await fetch(`/api/charts/${lang}`);
    const d = await r.json();
    if (!d.songs?.length) throw new Error("empty");
    list.innerHTML = d.songs.map((t,i) => trackHTML(t,i,true)).join("");
    bind(list, d.songs);
  } catch {
    list.innerHTML = `<div class="state-msg"><i class="ti ti-wifi-off"></i><p>Couldn't load charts.</p></div>`;
  }
}

async function loadNewReleases(lang) {
  const row = $("new-releases");
  row.innerHTML = "";
  try {
    const r = await fetch(`/api/new-releases?lang=${lang}`);
    const d = await r.json();
    if (!d.albums?.length) return;
    row.innerHTML = d.albums.slice(0,15).map(a => cardHTML(a,"album")).join("");
    row.querySelectorAll(".card[data-albumid]").forEach(el =>
      el.addEventListener("click", () => openAlbum(el.dataset.albumid))
    );
  } catch {}
}

async function openAlbum(id) {
  if (!id) { toast("Album unavailable"); return; }
  const list  = $("chart-list");
  const title = $("chart-title");
  list.innerHTML = `<div class="state-msg"><div class="spinner"></div><p>Loading album…</p></div>`;
  goto("home");
  try {
    const r = await fetch(`/api/album/${id}`);
    const d = await r.json();
    if (!d.songs?.length) throw new Error("empty");
    title.textContent = d.title;
    S.queue = d.songs; S.queueIdx = -1;
    list.innerHTML =
      `<div style="display:flex;align-items:center;gap:14px;padding:0 18px 16px">
        ${d.image?`<img src="${esc(d.image)}" style="width:64px;height:64px;border-radius:10px;object-fit:cover;box-shadow:var(--shadow-md)" alt="">` : ""}
        <div style="flex:1;min-width:0">
          <div style="font-size:15px;font-weight:700;color:var(--black)">${esc(d.title)}</div>
          <div style="font-size:12px;color:var(--gray-400);margin-top:3px">${esc(d.artist)} · ${esc(d.year||"")} · ${d.songs.length} songs</div>
          <div style="display:flex;gap:8px;margin-top:8px;align-items:center;flex-wrap:wrap">
            ${d.language?`<span class="badge badge-purple">${esc(d.language)}</span>`:""}
            <button id="album-play-all" style="display:flex;align-items:center;gap:5px;background:var(--grad-brand);color:#fff;border:none;border-radius:20px;padding:6px 14px;font-size:12px;font-weight:600;cursor:pointer">
              <i class="ti ti-player-play"></i> Play all
            </button>
            <button id="album-add-lib" style="display:flex;align-items:center;gap:5px;background:var(--gray-100);color:var(--purple);border:none;border-radius:20px;padding:6px 14px;font-size:12px;font-weight:600;cursor:pointer">
              <i class="ti ti-plus"></i> Add to Library
            </button>
          </div>
        </div>
      </div>` +
      d.songs.map((t,i) => trackHTML(t,i,true)).join("");
    bind(list, d.songs);
    const playAll = $("album-play-all");
    if (playAll) playAll.addEventListener("click", () => play(d.songs[0], d.songs, 0));
    const addLib = $("album-add-lib");
    if (addLib) addLib.addEventListener("click", () => addAlbumToLibrary(d));
  } catch {
    list.innerHTML = `<div class="state-msg"><p>Couldn't load album.</p></div>`;
  }
}

/* ── Search ─────────────────────────────────────────────────────────────── */
function initSearch() {
  const inp = $("search-input");
  const clr = $("search-clear");
  let timer;
  inp.addEventListener("input", e => {
    const q = e.target.value.trim();
    S.searchQ = q;
    clr.classList.toggle("vis", !!q);
    clearTimeout(timer);
    timer = setTimeout(() => doSearch(q), 420);
  });
  clr.addEventListener("click", () => {
    inp.value = ""; S.searchQ = ""; clr.classList.remove("vis");
    $("search-results").innerHTML = `<div class="state-msg"><i class="ti ti-search"></i><p>Search for songs,<br>artists, or albums</p></div>`;
    inp.focus();
  });
  $$(".s-chip").forEach(el => {
    el.addEventListener("click", () => {
      $$(".s-chip").forEach(c => c.classList.remove("active"));
      el.classList.add("active"); S.sType = el.dataset.stype;
      const moodMode = S.sType === "mood";
      // Toggle the mood panel vs the normal search box
      const mp = $("mood-panel"), st = $(".search-top");
      if (mp) mp.style.display = moodMode ? "block" : "none";
      if (st) st.style.display = moodMode ? "none" : "";
      if (moodMode) {
        if (!S.aiEnabled) {
          $("search-results").innerHTML = `<div class="state-msg"><i class="ti ti-sparkles"></i><p>Add your Gemini key to<br>config.json to use Mood playlists</p></div>`;
        } else {
          $("search-results").innerHTML = `<div class="state-msg"><i class="ti ti-sparkles"></i><p>Describe a vibe and Naada<br>will build a playlist for it</p></div>`;
        }
      } else if (S.searchQ) {
        doSearch(S.searchQ);
      }
    });
  });

  // Mood: build playlist from a free-text prompt
  const moodGo = $("mood-go"), moodInp = $("mood-input");
  if (moodGo && moodInp) {
    const run = () => { const v = moodInp.value.trim(); if (v) buildMoodPlaylist(v); };
    moodGo.addEventListener("click", run);
    moodInp.addEventListener("keydown", e => { if (e.key === "Enter") run(); });
  }
  $$(".mood-tag").forEach(tag =>
    tag.addEventListener("click", () => {
      const moodInp = $("mood-input");
      if (moodInp) { moodInp.value = tag.textContent; buildMoodPlaylist(tag.textContent); }
    }));
}

async function buildMoodPlaylist(prompt) {
  const res = $("search-results");
  if (!res) return;
  res.innerHTML = `<div class="state-msg"><div class="spinner"></div><p>Curating "${esc(prompt)}"…<br><span style="font-size:11px;color:var(--gray-400)">finding songs for your vibe</span></p></div>`;
  try {
    const r = await fetch("/api/ai/mood", {
      method: "POST", headers: {"Content-Type":"application/json"},
      body: JSON.stringify({ prompt, lang: S.homeLang || "" })
    });
    const d = await r.json();
    const songs = d.songs || [];
    if (!songs.length) {
      res.innerHTML = `<div class="state-msg"><i class="ti ti-mood-empty"></i><p>Couldn't build that playlist.<br>Try a different vibe.</p></div>`;
      return;
    }
    S.queue = songs; S.queueIdx = -1;
    const header =
      `<div class="mood-result-head">
        <div class="mood-result-title">${esc(d.title || prompt)}</div>
        ${d.description ? `<div class="mood-result-desc">${esc(d.description)}</div>` : ""}
        <div class="mood-result-actions">
          <button class="mood-play-all" id="mood-play-all"><i class="ti ti-player-play"></i> Play all</button>
          <button class="mood-save" id="mood-save"><i class="ti ti-plus"></i> Save as playlist</button>
        </div>
      </div>`;
    res.innerHTML = header + songs.map((t,i) => trackHTML(t,i,false)).join("");
    bind(res, songs);
    const playAll = $("mood-play-all");
    if (playAll) playAll.addEventListener("click", () => play(songs[0], songs, 0));
    const saveBtn = $("mood-save");
    if (saveBtn) saveBtn.addEventListener("click", () =>
      addAlbumToLibrary({ title: d.title || prompt, songs }));
  } catch {
    res.innerHTML = `<div class="state-msg"><i class="ti ti-wifi-off"></i><p>Mood playlist unavailable right now</p></div>`;
  }
}

async function doSearch(q) {
  if (!q) return;
  const res = $("search-results");
  res.innerHTML = `<div class="state-msg"><div class="spinner"></div><p>Searching…</p></div>`;
  try {
    if (S.sType === "songs") {
      const r = await fetch(`/api/search?q=${encodeURIComponent(q)}`);
      const d = await r.json();
      const songs = d.songs || [];
      if (!songs.length) { res.innerHTML = `<div class="state-msg"><i class="ti ti-music-off"></i><p>No songs found</p></div>`; return; }
      S.queue = songs; S.queueIdx = -1;
      res.innerHTML = songs.map((t,i) => trackHTML(t,i,false)).join("");
      bind(res, songs);
    } else if (S.sType === "albums") {
      const r = await fetch(`/api/search/albums?q=${encodeURIComponent(q)}`);
      const d = await r.json();
      const albums = d.albums || [];
      if (!albums.length) { res.innerHTML = `<div class="state-msg"><p>No albums found</p></div>`; return; }
      res.innerHTML = `<div style="display:grid;grid-template-columns:1fr 1fr;gap:14px;padding:0 18px">` +
        albums.map(a => cardHTML(a,"album")).join("") + `</div>`;
      res.querySelectorAll(".card[data-albumid]").forEach(el =>
        el.addEventListener("click", () => openAlbum(el.dataset.albumid))
      );
    } else {
      const r = await fetch(`/api/search/artists?q=${encodeURIComponent(q)}`);
      const d = await r.json();
      const artists = d.artists || [];
      if (!artists.length) { res.innerHTML = `<div class="state-msg"><p>No artists found</p></div>`; return; }
      res.innerHTML = artists.map(a =>
        `<div class="track" style="cursor:pointer" data-artistid="${esc(a.id)}">
          <div class="t-img" style="border-radius:50%">
            ${a.image ? `<img src="${esc(a.image)}" alt="" style="border-radius:50%">` : `<span class="t-img-ph">🎤</span>`}
          </div>
          <div class="t-info"><div class="t-title">${esc(a.name)}</div><div class="t-meta">Artist</div></div>
          <i class="ti ti-chevron-right" style="color:var(--gray-300);font-size:18px"></i>
        </div>`
      ).join("");
      res.querySelectorAll("[data-artistid]").forEach(el =>
        el.addEventListener("click", () => openArtist(el.dataset.artistid))
      );
    }
  } catch {
    res.innerHTML = `<div class="state-msg"><i class="ti ti-wifi-off"></i><p>Search failed.<br>Check your connection.</p></div>`;
  }
}

async function openArtist(id) {
  const res = $("search-results");
  res.innerHTML = `<div class="state-msg"><div class="spinner"></div><p>Loading artist…</p></div>`;
  try {
    const r = await fetch(`/api/artist/${id}`);
    const d = await r.json();
    const songs = d.songs || [];
    S.queue = songs;
    res.innerHTML =
      `<div style="display:flex;flex-direction:column;align-items:center;padding:20px 18px 16px;text-align:center">
        <div style="width:90px;height:90px;border-radius:50%;overflow:hidden;margin-bottom:12px;box-shadow:var(--shadow-lg);background:var(--grad-brand);display:flex;align-items:center;justify-content:center;font-size:36px">
          ${d.image?`<img src="${esc(d.image)}" style="width:100%;height:100%;object-fit:cover" alt="">` : "🎤"}
        </div>
        <div style="font-size:20px;font-weight:800;color:var(--black)">${esc(d.name)}</div>
      </div>` +
      `<div class="sec-hdr"><span class="sec-title">Top Songs</span></div>` +
      songs.map((t,i) => trackHTML(t,i,true)).join("");
    bind(res, songs);
  } catch {
    res.innerHTML = `<div class="state-msg"><p>Couldn't load artist.</p></div>`;
  }
}

/* ── Playback ────────────────────────────────────────────────────────────── */
async function play(track, queue=S.queue, idx=-1) {
  window.ndlog && window.ndlog(`play() called: ${track?.title} (loading=${S.loading})`);
  if (S.loading) { window.ndlog && window.ndlog("  BLOCKED: already loading"); return; }
  S.track    = track;
  S.queue    = queue;
  S.queueIdx = idx >= 0 ? idx : queue.findIndex(t => t.id === track.id);
  S.loading  = true; S.playing = false;

  updateMeta(track);
  $("mini-player").classList.add("active");
  $("player-loader").classList.add("vis");
  setPlayIcons(true);
  vinylPlay(false);

  // Add to history
  S.history = [track, ...S.history.filter(t => t.id !== track.id)].slice(0, 60);
  save();
  patchLists();
  S.shelfTouch.recents = Date.now();
  LS.set("nd_shelf_touch", S.shelfTouch);
  if (S.page === "home") { renderHomeLibraryShelves(); applyShelfVisibility(); }

  try {
    let d, streamUrl;
    // Use the URL we lined up earlier if it's for this exact track — this is
    // what lets the next song start while the app is backgrounded and the
    // Termux server may be throttled.
    if (S.nextUrl && S.nextUrl.id === track.id && S.nextUrl.url) {
      window.ndlog && window.ndlog(`  using prefetched url for ${track.id}`);
      d = { url: S.nextUrl.url };
      streamUrl = S.nextUrl.url;
      S.nextUrl = null;
    } else {
      window.ndlog && window.ndlog(`  fetching /api/song/${track.id}`);
      const r = await fetch(`/api/song/${track.id}`);
      d = await r.json();
      window.ndlog && window.ndlog(`  got response: url=${(d.url||"").slice(0,40)}`);
      streamUrl = d.url || "";
    }

    // Validate stream URL — must be absolute https:// to avoid 404 on local server
    if (!streamUrl || !streamUrl.startsWith("http")) {
      window.ndlog && window.ndlog(`  BAD URL: "${streamUrl}"`);
      throw new Error("no_stream_url");
    }

    Object.assign(track, d);
    updateMeta(track);

    // Stop previous audio cleanly
    audio.pause();
    audio.removeAttribute("src");
    audio.load();

    audio.src = streamUrl;
    audio.crossOrigin = "anonymous";   // needed for some CDN responses
    window.ndlog && window.ndlog(`  audio.src set, calling play()`);

    // Wait for enough data, with timeout
    await Promise.race([
      audio.play(),
      new Promise((_,rej) => setTimeout(() => rej(new Error("play_timeout")), 20000)),
    ]);
    window.ndlog && window.ndlog(`  ✓ audio.play() resolved — should be audible`);

    S.playing = true;
    setPlayIcons(true);
    vinylPlay(true);
    resetSkipErrors();
    scheduleAnecdote(track);
    updateMediaSession(track);

  } catch (err) {
    window.ndlog && window.ndlog(`  play() FAILED: ${err.message}`);
    console.error("play() error:", err.message);
    S.playing = false;
    setPlayIcons(false);
    vinylPlay(false);

    _skipErrors++;
    const msg = err.message === "no_stream_url"
      ? "Stream unavailable — skipping"
      : err.message === "play_timeout"
        ? "Slow connection — skipping"
        : "Couldn't play track — skipping";
    toast(msg);

    S.loading = false;
    $("player-loader").classList.remove("vis");
    setTimeout(playNext, 1200);
    return;
  } finally {
    S.loading = false;
    $("player-loader").classList.remove("vis");
    patchLists();
  }
}

// Sync the play/pause icon across the mini-player and the full now-screen.
// (This was being called but its definition was lost in an earlier edit —
//  the missing function is what crashed every play() call.)
function setPlayIcons(playing) {
  const cls = playing ? "ti ti-player-pause" : "ti ti-player-play";
  const pp  = $("pp-icon");      if (pp)  pp.className  = cls;
  const mpp = $("mini-pp-icon"); if (mpp) mpp.className = cls;
  // Bring the logo to life while music plays
  const logo = $("logo-mark");
  if (logo) logo.classList.toggle("playing", !!playing);
  // Reflect state in the OS media notification / lock screen
  if ("mediaSession" in navigator) {
    try { navigator.mediaSession.playbackState = playing ? "playing" : "paused"; } catch {}
  }
}

/* ── Media Session (lock-screen / notification metadata + controls) ───────
   Tells Android the track title, artist, and ALBUM ART so the media
   notification shows proper info and artwork. (Note: the small "source"
   badge in that notification is Chrome's own icon and can't be changed by a
   website — only a native app could. This controls everything else.) */
function updateMediaSession(t) {
  if (!("mediaSession" in navigator) || !t) return;
  try {
    const art = t.image || "";
    const artwork = art ? [
      { src: art, sizes: "150x150", type: "image/jpeg" },
      { src: art, sizes: "500x500", type: "image/jpeg" },
    ] : [];
    navigator.mediaSession.metadata = new MediaMetadata({
      title:  t.title  || "Naada",
      artist: t.artist || "",
      album:  t.album  || "Naada · నాద",
      artwork,
    });
    // Lock-screen / notification control buttons
    const set = (action, fn) => {
      try { navigator.mediaSession.setActionHandler(action, fn); } catch {}
    };
    set("play",  () => { audio.play(); S.playing = true; setPlayIcons(true); vinylPlay(true); patchLists(); });
    set("pause", () => { audio.pause(); S.playing = false; setPlayIcons(false); vinylPlay(false); patchLists(); });
    set("previoustrack", () => playPrev());
    set("nexttrack",     () => playNext());
    set("seekto", (d) => { if (d.seekTime != null && audio.duration) audio.currentTime = d.seekTime; });
    set("seekbackward",  (d) => { audio.currentTime = Math.max(0, audio.currentTime - (d.seekOffset||10)); });
    set("seekforward",   (d) => { audio.currentTime = Math.min(audio.duration||0, audio.currentTime + (d.seekOffset||10)); });
  } catch (e) {
    console.warn("mediaSession update failed", e);
  }
}

// Keep the OS notification's seek bar in sync with playback position
function updateMediaPositionState() {
  if (!("mediaSession" in navigator) || !navigator.mediaSession.setPositionState) return;
  if (!audio.duration || isNaN(audio.duration)) return;
  try {
    navigator.mediaSession.setPositionState({
      duration: audio.duration,
      playbackRate: audio.playbackRate || 1,
      position: Math.min(audio.currentTime, audio.duration),
    });
  } catch {}
}

function updateMeta(t) {
  // Full now-screen
  $("np-title").textContent  = t.title  || "—";
  $("np-artist").textContent = t.artist || "—";
  $("lyrics-title").textContent = t.title || "Lyrics";
  const img = $("np-art-img"), ph = $("np-art-ph");
  if (t.image) { img.src = t.image; img.style.display = "block"; ph.style.display = "none"; }
  else { img.style.display = "none"; ph.style.display = "flex"; }
  const la = $("lyrics-art");
  if (la) la.innerHTML = t.image ? `<img src="${esc(t.image)}" alt="">` : "🎵";

  // Mini-player
  $("mini-title").textContent  = t.title  || "—";
  $("mini-artist").textContent = t.artist || "";
  const mph = $("mini-art-ph"), ma = $("mini-art");
  if (t.image) { ma.style.backgroundImage = `url(${t.image})`; ma.classList.add("has-img"); if(mph) mph.style.display="none"; }
  else { ma.style.backgroundImage = ""; ma.classList.remove("has-img"); if(mph) mph.style.display="block"; }

  updateNpLike();
  const qb = $("queue-badge");
  if (qb) qb.textContent = `${S.queue.length} song${S.queue.length!==1?"s":""}`;

  // Tint the player from the album art (async, fails soft)
  if (typeof applyArtColors === "function") applyArtColors(t);
}

function vinylPlay(playing) {
  const img = $("np-art-img");
  const ph  = $("np-art-ph");
  if (playing) { img.classList.add("playing"); ph.classList.add("playing"); }
  else         { img.classList.remove("playing"); ph.classList.remove("playing"); }
}

function updateNpLike() {
  const l = S.track && isLiked(S.track.id);
  for (const id of ["np-like","mini-like"]) {
    const btn = $(id);
    if (!btn) continue;
    btn.classList.toggle("liked", !!l);
    btn.innerHTML = l ? `<i class="ti ti-heart-filled"></i>` : `<i class="ti ti-heart"></i>`;
  }
}

function togglePlay() {
  if (!S.track) return;
  if (audio.paused) {
    audio.play().then(() => { S.playing = true; setPlayIcons(true); vinylPlay(true); patchLists(); });
  } else {
    audio.pause(); S.playing = false; setPlayIcons(false); vinylPlay(false); patchLists();
  }
}

// Consecutive error guard — stops runaway skip loops
let _skipErrors = 0;
const MAX_SKIP_ERRORS = 3;

function resetSkipErrors() { _skipErrors = 0; }

function playNext() {
  if (!S.queue.length) return;
  if (_skipErrors >= MAX_SKIP_ERRORS) {
    _skipErrors = 0;
    S.playing = false;
    $("pp-icon").className = "ti ti-player-play";
    vinylPlay(false);
    toast("Several tracks unavailable — check connection or try a different search.", 4000);
    return;
  }
  const idx = S.shuffle
    ? Math.floor(Math.random() * S.queue.length)
    : (S.queueIdx + 1) % S.queue.length;
  play(S.queue[idx], S.queue, idx);
}

function playPrev() {
  if (audio.currentTime > 3) { audio.currentTime = 0; return; }
  if (!S.queue.length) return;
  const idx = S.shuffle
    ? Math.floor(Math.random() * S.queue.length)
    : (S.queueIdx - 1 + S.queue.length) % S.queue.length;
  play(S.queue[idx], S.queue, idx);
}

/* ── Audio events ────────────────────────────────────────────────────────── */
audio.addEventListener("timeupdate", () => {
  if (!audio.duration) return;
  const pct = (audio.currentTime / audio.duration) * 100;
  const bar = $("seek-bar");
  if (bar) {
    bar.value = pct;
    bar.style.background = `linear-gradient(to right,var(--purple) ${pct}%,var(--gray-200) ${pct}%)`;
  }
  const sc = $("seek-cur"), sd = $("seek-dur");
  if (sc) sc.textContent = fmt(audio.currentTime);
  if (sd) sd.textContent = fmt(audio.duration);
  const mp = $("mini-progress");
  if (mp) mp.style.width = pct + "%";
  if (_syncedLyrics.length) highlightSyncedLyric(audio.currentTime);
});

// Sync the OS notification's seek bar once per second (not every timeupdate)
audio.addEventListener("loadedmetadata", updateMediaPositionState);
setInterval(() => { if (!audio.paused) updateMediaPositionState(); }, 1000);

audio.addEventListener("ended", () => {
  if (S.repeat === "one") { audio.currentTime = 0; audio.play(); return; }
  if (S.repeat === "all" || S.queueIdx < S.queue.length - 1) { playNext(); return; }
  S.playing = false; setPlayIcons(false); vinylPlay(false); patchLists();
});
audio.addEventListener("waiting", () => $("player-loader").classList.add("vis"));
audio.addEventListener("canplay", () => $("player-loader").classList.remove("vis"));
audio.addEventListener("error",   () => { _skipErrors++; toast("Stream error — skipping"); setTimeout(playNext, 900); });

$("seek-bar").addEventListener("input", e => {
  if (audio.duration) audio.currentTime = (e.target.value / 100) * audio.duration;
});

/* ── Player controls ────────────────────────────────────────────────────── */
$("ctrl-play").addEventListener("click", togglePlay);
$("ctrl-prev").addEventListener("click", playPrev);
$("ctrl-next").addEventListener("click", playNext);

$("ctrl-shuffle").addEventListener("click", () => {
  S.shuffle = !S.shuffle; save();
  $("ctrl-shuffle").classList.toggle("active", S.shuffle);
  toast(S.shuffle ? "Shuffle on" : "Shuffle off");
});

$("ctrl-repeat").addEventListener("click", () => {
  S.repeat = S.repeat === false ? "all" : S.repeat === "all" ? "one" : false;
  save();
  const btn = $("ctrl-repeat");
  $("repeat-icon").className = S.repeat === "one" ? "ti ti-repeat-once" : "ti ti-repeat";
  btn.classList.toggle("active", !!S.repeat);
  toast(S.repeat === "one" ? "Repeat one" : S.repeat === "all" ? "Repeat all" : "Repeat off");
});

// Init control states from storage
$("ctrl-shuffle").classList.toggle("active", S.shuffle);
$("ctrl-repeat").classList.toggle("active", !!S.repeat);
if (S.repeat === "one") $("repeat-icon").className = "ti ti-repeat-once";

/* ── Like ────────────────────────────────────────────────────────────────── */
function toggleLike(id, tracks=[]) {
  const t = tracks.find(x => x.id === id) || S.liked.find(x => x.id === id)
         || S.history.find(x => x.id === id) || (S.track?.id === id ? S.track : null);
  if (!t) return;
  if (isLiked(id)) {
    S.liked = S.liked.filter(x => x.id !== id);
    toast("Removed from liked");
  } else {
    S.liked.unshift({...t});
    toast("❤ Liked");
  }
  save(); updateNpLike(); patchLists();
  touchShelf("liked");
  // Refresh the home shelves so the liked row updates immediately
  renderHomeLibraryShelves();
  if (S.page === "library") renderLibrary();
}

$("np-like").addEventListener("click", () => { if (S.track) toggleLike(S.track.id, [S.track]); });

/* ── Patch all visible lists ─────────────────────────────────────────────── */
function patchLists() {
  document.querySelectorAll(".track[data-id]").forEach(el => {
    const id     = el.dataset.id;
    const now    = S.track?.id === id;
    const liked  = isLiked(id);
    el.classList.toggle("now", now);
    el.querySelectorAll(".t-like").forEach(btn => {
      btn.classList.toggle("liked", liked);
      btn.innerHTML = liked ? `<i class="ti ti-heart-filled"></i>` : `<i class="ti ti-heart"></i>`;
    });
    const numEl = el.querySelector(".t-num, .bars");
    if (numEl) {
      if (now && S.playing && !numEl.classList.contains("bars")) {
        numEl.outerHTML = `<div class="bars"><span></span><span></span><span></span></div>`;
      } else if ((!now || !S.playing) && numEl.classList.contains("bars")) {
        const idx = parseInt(el.dataset.idx);
        numEl.outerHTML = `<span class="t-num">${!isNaN(idx) ? idx+1 : "•"}</span>`;
      }
    }
  });
}

/* ── Library ─────────────────────────────────────────────────────────────── */
function renderLibrary() {
  renderHomeLibraryShelves();
  $("stat-liked").textContent     = S.liked.length;
  $("stat-playlists").textContent = S.playlists.length;
  $("stat-history").textContent   = S.history.length;

  const ll = $("liked-list");
  if (S.liked.length) {
    ll.innerHTML = S.liked.map((t,i) => trackHTML(t,i,false)).join("");
    bind(ll, S.liked);
  } else {
    ll.innerHTML = `<div class="state-msg"><i class="ti ti-heart"></i><p>Like songs to save them here</p></div>`;
  }

  renderPlaylists();

  const hl = $("history-list");
  if (S.history.length) {
    hl.innerHTML = S.history.slice(0,40).map((t,i) => trackHTML(t,i,false)).join("");
    bind(hl, S.history.slice(0,40));
  } else {
    hl.innerHTML = `<div class="state-msg"><i class="ti ti-history"></i><p>Your history appears here</p></div>`;
  }
}

function renderPlaylists() {
  const grid = $("playlist-grid");
  if (typeof renderFolders === "function") renderFolders();
  const items = typeof visiblePlaylists === "function" ? visiblePlaylists() : S.playlists;

  if (!items.length) {
    const inFolder = typeof S !== "undefined" && S.viewFolder;
    grid.innerHTML = `<div style="grid-column:1/-1"><div class="state-msg" style="padding:20px">
      <i class="ti ti-playlist"></i>
      <p>${inFolder
          ? "This folder is empty.<br>Drag an album onto it, or use ⋮ → Move to folder."
          : (S.playlists.length ? "Every album is filed in a folder." : "No albums or playlists yet")}</p>
    </div></div>`;
    return;
  }

  // Pinned playlists always sort to the top
  const ordered = [...items].sort((a,b) => (b.pinned?1:0) - (a.pinned?1:0));
  grid.innerHTML = ordered.map(pl => {
    const first = pl.songs?.[0];
    return `<div class="pl-card" data-plid="${esc(pl.id)}">
      <button class="pl-card-menu" data-plmenu="${esc(pl.id)}" aria-label="Album options"><i class="ti ti-dots"></i></button>
      ${pl.pinned ? `<span class="pin-badge"><i class="ti ti-pin-filled"></i></span>` : ""}
      <div class="pl-card-img">
        ${first?.image ? `<img src="${esc(first.image)}" alt="">` : "🎵"}
      </div>
      <div class="pl-card-name">${esc(pl.name)}</div>
      <div class="pl-card-count">${pl.songs.length} song${pl.songs.length!==1?"s":""}</div>
    </div>`;
  }).join("");
  grid.querySelectorAll(".pl-card[data-plid]").forEach(el =>
    el.addEventListener("click", e => {
      const menuBtn = e.target.closest(".pl-card-menu");
      if (menuBtn) { e.stopPropagation(); openPlMenu(menuBtn.dataset.plmenu); return; }
      openPlaylistDetail(el.dataset.plid);
    })
  );
  if (typeof initAlbumDragToFolder === "function") initAlbumDragToFolder();
}

$("liked-shuffle").addEventListener("click", () => {
  if (!S.liked.length) return;
  S.shuffle = true; save(); $("ctrl-shuffle").classList.add("active");
  S.queue = [...S.liked]; S.queueIdx = 0;
  const idx = Math.floor(Math.random() * S.liked.length);
  play(S.liked[idx], S.liked, idx);
  toast("Shuffling liked songs");
});

$("clear-history").addEventListener("click", () => {
  if (!S.history.length) return;
  S.history = []; save(); renderLibrary(); renderHomeLibraryShelves(); toast("History cleared");
});

// Clear all liked songs (with a confirm, since it's destructive)
$("liked-clear").addEventListener("click", () => {
  if (!S.liked.length) return;
  if (!confirm(`Remove all ${S.liked.length} liked songs?`)) return;
  S.liked = []; save(); updateNpLike(); patchLists();
  renderLibrary(); renderHomeLibraryShelves(); toast("Liked songs cleared");
});

// Stat cards jump to their section
function scrollToSection(id) {
  const el = $(id);
  if (el) el.scrollIntoView({ behavior: "smooth", block: "start" });
}
$("stat-card-liked").addEventListener("click",     () => scrollToSection("sec-liked"));
$("stat-card-playlists").addEventListener("click", () => scrollToSection("sec-playlists"));
$("stat-card-history").addEventListener("click",   () => scrollToSection("sec-history"));

/* ── Playlists ───────────────────────────────────────────────────────────── */
$("new-playlist-btn").addEventListener("click", () => {
  $("pl-name-input").value = "";
  $("create-pl-modal").classList.add("open");
  setTimeout(() => $("pl-name-input").focus(), 100);
});

$("pl-create-confirm").addEventListener("click", () => {
  // Rename mode and create-from-selection mode are handled by their own
  // listeners (registered later, so they run after this one) — bail here.
  if (S.renamePlId || S.newPlWithTracks) return;
  const name = $("pl-name-input").value.trim();
  if (!name) return;
  S.playlists.unshift({ id: uid(), name, songs: [] });
  save(); $("create-pl-modal").classList.remove("open");
  renderPlaylists(); toast(`Playlist "${name}" created`);
  $("stat-playlists").textContent = S.playlists.length;
});

$("pl-create-cancel").addEventListener("click", () => $("create-pl-modal").classList.remove("open"));
$("pl-name-input").addEventListener("keydown", e => { if (e.key === "Enter") $("pl-create-confirm").click(); });

function openPlaylistDetail(plid) {
  const pl = S.playlists.find(p => p.id === plid);
  if (!pl) return;
  S.viewPlId = plid;
  S.plEditMode = false;
  S.plSel = new Set();
  const et = $("pl-edit-toggle"); if (et) et.textContent = "Select";
  const bar = $("bulk-bar"); if (bar) bar.classList.remove("show");
  const rh = $("pl-reorder-hint"); if (rh) rh.style.display = "none";
  $("pl-detail-title").textContent = pl.name;
  renderPlaylistDetail(pl);
  $("pl-detail-sheet").classList.add("open");
}

$("pl-detail-close").addEventListener("click", () => {
  $("pl-detail-sheet").classList.remove("open");
  S.plEditMode = false; S.plSel = new Set();
  const bar = $("bulk-bar"); if (bar) bar.classList.remove("show");
});

$("pl-play-all").addEventListener("click", () => {
  const pl = S.playlists.find(p => p.id === S.viewPlId);
  if (!pl || !pl.songs.length) return;
  S.queue = pl.songs;
  play(pl.songs[0], pl.songs, 0);
  $("pl-detail-sheet").classList.remove("open");
});


/* ── Add a whole album to library as a playlist ──────────────────────────── */
function addAlbumToLibrary(album) {
  if (!album || !album.songs?.length) { toast("Nothing to add"); return; }
  // If a playlist with this album name already exists, merge new songs in
  let pl = S.playlists.find(p => p.name === album.title);
  if (!pl) {
    pl = { id: uid(), name: album.title || "Album", songs: [] };
    S.playlists.unshift(pl);
  }
  let added = 0;
  for (const s of album.songs) {
    if (!pl.songs.some(x => x.id === s.id)) { pl.songs.push({...s}); added++; }
  }
  // Move this playlist to the front of the list so it's the first one shown
  S.playlists = [pl, ...S.playlists.filter(p => p.id !== pl.id)];
  save();
  touchShelf("playlists");
  renderHomeLibraryShelves();
  toast(added ? `Added ${added} song${added!==1?"s":""} to Library` : "Already in Library");
}

/* ── Add to playlist ─────────────────────────────────────────────────────── */
$("np-add-pl").addEventListener("click", () => {
  if (!S.track) { toast("Play a song first"); return; }
  S.addPlTrack = S.track;
  openAddToPlaylist();
});

/* Single-track mode: uses S.addPlTrack (legacy callers unchanged).
   Bulk mode: pass { bulk: tracks[], mode: "copy"|"move" } — "move" also
   removes the tracks from the currently-open playlist. This is how you
   curate a new collection out of an existing one. */
function openAddToPlaylist(bulkOpts = null) {
  S.addPlBulk = bulkOpts;
  const list = $("pl-select-list");
  // In bulk mode, don't offer the playlist we're moving/copying from
  const targets = bulkOpts
    ? S.playlists.filter(p => p.id !== S.viewPlId)
    : S.playlists;

  const finish = (pl, added) => {
    // "move" removes the originals from the source playlist
    if (bulkOpts?.mode === "move") {
      const src = S.playlists.find(p => p.id === S.viewPlId);
      if (src) {
        const ids = new Set(bulkOpts.bulk.map(t => t.id));
        src.songs = src.songs.filter(s => !ids.has(s.id));
        renderPlaylistDetail(src);
      }
      S.plSel = new Set(); updateBulkBar();
    }
    S.playlists = [pl, ...S.playlists.filter(p => p.id !== pl.id)];
    save(); $("add-pl-sheet").classList.remove("open");
    S.addPlBulk = null;
    touchShelf("playlists");
    renderHomeLibraryShelves();
    if (S.page === "library") renderLibrary();
    const verb = bulkOpts?.mode === "move" ? "Moved" : "Added";
    toast(added ? `${verb} ${added} song${added!==1?"s":""} to "${pl.name}"` : `Already in "${pl.name}"`);
  };

  const addTracksTo = pl => {
    const tracks = bulkOpts ? bulkOpts.bulk : (S.addPlTrack ? [S.addPlTrack] : []);
    let added = 0;
    for (const t of tracks) {
      if (!pl.songs.some(s => s.id === t.id)) { pl.songs.push({...t}); added++; }
    }
    finish(pl, added);
  };
  S._addTracksTo = addTracksTo;   // used by the "+ New" flow below

  if (!targets.length) {
    list.innerHTML = `<div class="state-msg"><i class="ti ti-playlist"></i><p>No other playlists yet.<br>Tap + to create one${bulkOpts ? " from your selection" : ""}.</p></div>`;
  } else {
    list.innerHTML = targets.map(pl => {
      const first = pl.songs?.[0];
      return `<div class="pl-select-item" data-plid="${esc(pl.id)}">
        <div class="pl-select-thumb">
          ${first?.image ? `<img src="${esc(first.image)}" alt="">` : "🎵"}
        </div>
        <div>
          <div class="pl-select-name">${esc(pl.name)}</div>
          <div class="pl-select-count">${pl.songs.length} songs</div>
        </div>
      </div>`;
    }).join("");
    list.querySelectorAll(".pl-select-item").forEach(el => {
      el.addEventListener("click", () => {
        const pl = S.playlists.find(p => p.id === el.dataset.plid);
        if (pl) addTracksTo(pl);
      });
    });
  }
  $("add-pl-sheet").classList.add("open");
}

$("add-pl-close").addEventListener("click", () => $("add-pl-sheet").classList.remove("open"));

/* ── Queue page ──────────────────────────────────────────────────────────── */
function renderQueue() {
  const el = $("queue-list");
  $("queue-badge").textContent = `${S.queue.length} song${S.queue.length!==1?"s":""}`;
  if (!S.queue.length) {
    el.innerHTML = `<div class="state-msg"><i class="ti ti-list"></i><p>Queue is empty</p></div>`;
    return;
  }
  el.innerHTML = S.queue.map((t,i) => trackHTML(t,i,true)).join("");
  bind(el, S.queue);
}

/* ── Lyrics (JioSaavn + LRCLIB, synced support) ──────────────────────────── */
let _syncedLyrics = [];   // [{t:seconds, text}] when synced lyrics available
let _syncedActive  = -1;

function parseLRC(lrc) {
  // Parse "[mm:ss.xx] text" lines into {t, text}
  const out = [];
  for (const line of lrc.split("\n")) {
    const m = line.match(/^((?:\[\d{1,2}:\d{2}(?:\.\d{1,3})?\])+)(.*)$/);
    if (!m) continue;
    const text = m[2].trim();
    const stamps = m[1].match(/\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]/g) || [];
    for (const st of stamps) {
      const mm = st.match(/\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]/);
      if (mm) {
        const t = (+mm[1])*60 + (+mm[2]) + (mm[3] ? (+mm[3])/(mm[3].length===2?100:1000) : 0);
        out.push({ t, text });
      }
    }
  }
  return out.sort((a,b) => a.t - b.t);
}

function highlightSyncedLyric(cur) {
  let idx = -1;
  for (let i = 0; i < _syncedLyrics.length; i++) {
    if (_syncedLyrics[i].t <= cur) idx = i; else break;
  }
  if (idx === _syncedActive) return;
  _syncedActive = idx;
  const rows = $("lyrics-text").querySelectorAll(".lrc-line");
  rows.forEach((r,i) => r.classList.toggle("active", i === idx));
  const active = rows[idx];
  if (active && $("lyrics-sheet").classList.contains("open")) {
    active.scrollIntoView({ block: "center", behavior: "smooth" });
  }
}

async function openLyrics() {
  if (!S.track) { toast("Play a song first"); return; }
  $("lyrics-sheet").classList.add("open");
  const txt  = $("lyrics-text");
  const copy = $("lyrics-copy");
  const badge= $("lyrics-source");
  _syncedLyrics = []; _syncedActive = -1;
  if (badge) badge.textContent = "";
  txt.innerHTML = `<div style="padding:40px 0;display:flex;justify-content:center"><div class="spinner"></div></div>`;
  copy.textContent = "";
  try {
    const t = S.track;
    const qs = new URLSearchParams({
      title: t.title || "", artist: t.artist || "",
      album: t.album || "", duration: String(t.duration || "")
    });
    const r = await fetch(`/api/lyrics/${t.lyrics_id || t.id}?${qs}`);
    const d = await r.json();

    if (d.synced) {
      _syncedLyrics = parseLRC(d.synced);
      if (_syncedLyrics.length) {
        txt.innerHTML = _syncedLyrics.map(l =>
          `<p class="lrc-line">${l.text ? esc(l.text) : "&nbsp;"}</p>`).join("");
        if (badge) badge.textContent = "SYNCED";
        highlightSyncedLyric(audio.currentTime);
        copy.textContent = "Lyrics via LRCLIB";
        return;
      }
    }
    if (d.lyrics) {
      txt.innerHTML = d.lyrics.split("\n").map(l =>
        `<p${!l.trim()?' class="blank"':""}>${l ? esc(l) : "&nbsp;"}</p>`).join("");
      if (badge) badge.textContent = (d.source || "").toUpperCase();
      copy.textContent = d.copyright || (d.source === "lrclib" ? "Lyrics via LRCLIB" : "");
      return;
    }
    throw new Error("none");
  } catch {
    txt.innerHTML = `<p style="color:var(--gray-400);font-size:14px">No lyrics found for this song.</p>`;
  }
}

$("btn-lyrics").addEventListener("click", openLyrics);
$("lyrics-close").addEventListener("click", () => $("lyrics-sheet").classList.remove("open"));

/* ── Sleep timer ─────────────────────────────────────────────────────────── */
$("btn-sleep").addEventListener("click", () => {
  if (S.sleepEnd) {
    const rem = Math.ceil((S.sleepEnd - Date.now()) / 60000);
    toast(`Sleep timer: ${rem} min left`);
  }
  $("sleep-modal").classList.add("open");
});
$("sleep-dismiss").addEventListener("click", () => $("sleep-modal").classList.remove("open"));

$$(".modal-opt[data-mins]").forEach(el => {
  el.addEventListener("click", () => {
    $("sleep-modal").classList.remove("open");
    const mins = parseInt(el.dataset.mins);
    clearTimeout(S.sleepTimer); S.sleepEnd = null;
    if (mins === 0) { toast("Sleep timer cancelled"); return; }
    S.sleepEnd = Date.now() + mins * 60000;
    S.sleepTimer = setTimeout(() => {
      audio.pause(); S.playing = false;
      setPlayIcons(false);
      vinylPlay(false); S.sleepEnd = null;
      toast("Sleep timer — goodnight 🌙", 4000);
    }, mins * 60000);
    $("btn-sleep").classList.add("active");
    toast(`Sleep timer: ${mins} min`);
  });
});

/* ── Keyboard shortcuts ──────────────────────────────────────────────────── */
document.addEventListener("keydown", e => {
  if (["INPUT","TEXTAREA"].includes(e.target.tagName)) return;
  if (e.code === "Space")      { e.preventDefault(); togglePlay(); }
  if (e.code === "ArrowRight") { e.preventDefault(); playNext(); }
  if (e.code === "ArrowLeft")  { e.preventDefault(); playPrev(); }
  if (e.key === "l" || e.key === "L") { if (S.track) toggleLike(S.track.id, [S.track]); }
});

/* ── Service worker ──────────────────────────────────────────────────────── */
if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}


/* ═══════════════════════════════════════════════════════════════════════
   NEW: Home shelves, mini-player/now-screen, editing, settings
═══════════════════════════════════════════════════════════════════════ */

/* ── Home settings (which shelves show, default lang) ────────────────────── */
const SHELF_DEFS = [
  { id:"pinned",       label:"Pinned songs",           personal:true },
  { id:"recents",      label:"Jump back in (recents)", personal:true },
  { id:"liked",        label:"Your liked songs",       personal:true },
  { id:"playlists",    label:"Your playlists",          personal:true },
  { id:"trending",     label:"Trending now" },
  { id:"chartbusters", label:"Chartbusters" },
  { id:"albums",       label:"New releases" },
  { id:"movies",       label:"Latest movie songs" },
  { id:"featured",     label:"Made for you" },
];
// Migrate older saved configs so the new shelf appears for existing users
S.homeCfg = LS.get("nd_homecfg", { shelves: SHELF_DEFS.map(s=>s.id), defaultLang: "telugu" });
if (!S.homeCfg.shelves.includes("chartbusters")) {
  const i = S.homeCfg.shelves.indexOf("trending");
  if (i !== -1) S.homeCfg.shelves.splice(i+1, 0, "chartbusters");
  else S.homeCfg.shelves.push("chartbusters");
  LS.set("nd_homecfg", S.homeCfg);
}
if (!S.homeCfg.shelves.includes("pinned")) {
  S.homeCfg.shelves.unshift("pinned");
  LS.set("nd_homecfg", S.homeCfg);
}
// Per-shelf "last touched" times — personal shelves float to the top when used.
S.shelfTouch = LS.get("nd_shelf_touch", {});

// Mark a personal shelf as just-used so it floats to the top next render.
function touchShelf(id) {
  S.shelfTouch[id] = Date.now();
  LS.set("nd_shelf_touch", S.shelfTouch);
  applyShelfVisibility();
}

/* ── Collapsible shelves ─────────────────────────────────────────────────
   Tap a shelf header to fold/unfold it. Collapsed state is remembered so the
   home screen stays the way you left it. */
S.collapsed = LS.get("nd_collapsed", {});

function applyCollapsed() {
  for (const def of SHELF_DEFS) {
    const sec = $("shelf-" + def.id);
    if (sec) sec.classList.toggle("collapsed", !!S.collapsed[def.id]);
  }
}

function toggleShelfCollapse(id) {
  S.collapsed[id] = !S.collapsed[id];
  LS.set("nd_collapsed", S.collapsed);
  const sec = $("shelf-" + id);
  if (sec) sec.classList.toggle("collapsed", !!S.collapsed[id]);
}

// Delegated click on shelf headers — but ignore taps on the "See all" link
document.addEventListener("click", e => {
  const hdr = e.target.closest(".shelf-hdr");
  if (!hdr) return;
  if (e.target.closest(".sec-more")) return;   // let "See all" do its own thing
  const id = hdr.dataset.collapse;
  if (id) toggleShelfCollapse(id);
});

function applyShelfVisibility() {
  const container = $("page-home");
  if (!container) return;
  // 1) Toggle visibility per saved config
  for (const def of SHELF_DEFS) {
    const el = $("shelf-" + def.id);
    if (!el) continue;
    let show = S.homeCfg.shelves.includes(def.id);
    // Personal shelves with nothing in them are just a header over dead
    // space — hide them until they have something to say.
    if (def.id === "pinned"    && !S.pinned.length)    show = false;
    if (def.id === "liked"     && !S.liked.length)     show = false;
    if (def.id === "playlists" && !S.playlists.length) show = false;
    if (def.id === "recents"   && !S.history.length)   show = false;
    el.style.display = show ? "" : "none";
  }
  // 2) Order: personal shelves first (most-recently-touched on top),
  //    then discovery shelves in their natural order. This is what makes a
  //    just-updated playlist/liked shelf jump up so you never lose track of it.
  const personal = SHELF_DEFS.filter(d => d.personal)
    .slice()
    .sort((a,b) => (S.shelfTouch[b.id]||0) - (S.shelfTouch[a.id]||0));
  const discovery = SHELF_DEFS.filter(d => !d.personal);
  const ordered = [...personal, ...discovery];
  // Re-append in computed order (the hero + language pill stay above, since
  // not .shelf elements and stay where they are).
  for (const def of ordered) {
    const el = $("shelf-" + def.id);
    if (el) container.appendChild(el);
  }
}

function renderHomeLibraryShelves() {
  // Pinned songs — the shelf hides itself entirely when nothing is pinned,
  // so it never nags; pinning a first song makes it appear at the top.
  const pinShelf = $("shelf-pinned"), pinRow = $("home-pinned");
  if (pinShelf && pinRow) {
    if (S.pinned.length && S.homeCfg.shelves.includes("pinned")) {
      pinShelf.style.display = "";
      pinRow.innerHTML = S.pinned.map(t =>
        `<div class="mini-card" data-id="${esc(t.id)}">
           <div class="mini-card-img" style="position:relative">
             ${t.image?`<img src="${esc(t.image)}" alt="">`:"🎵"}
             <span class="pin-badge"><i class="ti ti-pin-filled"></i></span>
           </div>
           <div class="mini-card-title">${esc(t.title)}</div>
           <div class="mini-card-sub">${esc(t.artist||"")}</div>
         </div>`).join("");
      pinRow.querySelectorAll(".mini-card[data-id]").forEach(el =>
        el.addEventListener("click", () => {
          const t = S.pinned.find(x => x.id === el.dataset.id);
          if (t) play(t, S.pinned, S.pinned.indexOf(t));
        }));
    } else {
      pinShelf.style.display = "none";
    }
  }

  // Recents
  const rec = $("home-recents");
  if (rec) {
    if (S.history.length) {
      rec.innerHTML = S.history.slice(0,12).map(t =>
        `<div class="mini-card" data-id="${esc(t.id)}">
           <div class="mini-card-img">${t.image?`<img src="${esc(t.image)}" alt="">`:"🎵"}</div>
           <div class="mini-card-title">${esc(t.title)}</div>
         </div>`).join("");
      rec.querySelectorAll(".mini-card[data-id]").forEach(el =>
        el.addEventListener("click", () => {
          const t = S.history.find(x => x.id === el.dataset.id);
          if (t) play(t, S.history, S.history.indexOf(t));
        }));
    } else {
      rec.innerHTML = `<div class="shelf-empty">Nothing played yet</div>`;
    }
  }
  // Liked preview
  const lk = $("home-liked");
  if (lk) {
    if (S.liked.length) {
      lk.innerHTML = S.liked.slice(0,12).map(t =>
        `<div class="mini-card" data-id="${esc(t.id)}">
           <div class="mini-card-img">${t.image?`<img src="${esc(t.image)}" alt="">`:"🎵"}</div>
           <div class="mini-card-title">${esc(t.title)}</div>
         </div>`).join("");
      lk.querySelectorAll(".mini-card[data-id]").forEach(el =>
        el.addEventListener("click", () => {
          const t = S.liked.find(x => x.id === el.dataset.id);
          if (t) play(t, S.liked, S.liked.indexOf(t));
        }));
    } else {
      lk.innerHTML = `<div class="shelf-empty">Like songs to see them here</div>`;
    }
  }
  // Playlists
  const pl = $("home-playlists");
  if (pl) {
    if (S.playlists.length) {
      pl.innerHTML = S.playlists.slice(0,12).map(p => {
        const first = p.songs?.[0];
        return `<div class="mini-card" data-plid="${esc(p.id)}">
           <div class="mini-card-img pl">${first?.image?`<img src="${esc(first.image)}" alt="">`:"🎵"}</div>
           <div class="mini-card-title">${esc(p.name)}</div>
           <div class="mini-card-sub">${p.songs.length} songs</div>
         </div>`;
      }).join("");
      pl.querySelectorAll(".mini-card[data-plid]").forEach(el =>
        el.addEventListener("click", () => openPlaylistDetail(el.dataset.plid)));
    } else {
      pl.innerHTML = `<div class="shelf-empty">No playlists yet</div>`;
    }
  }
}

async function loadHomeShelves(lang) {
  renderHomeLibraryShelves();
  // Movies + featured come from the unified endpoint
  try {
    const r = await fetch(`/api/home/${lang}`);
    const d = await r.json();
    // Trending already handled by loadCharts; here we fill movies + featured
    const mv = $("home-movies");
    if (mv && d.new_albums) {
      const albums = d.new_albums.filter(a => a.id);
      mv.innerHTML = albums.slice(0,15).map(a => cardHTML(a,"album")).join("");
      mv.querySelectorAll(".card[data-albumid]").forEach(el =>
        el.addEventListener("click", () => openAlbum(el.dataset.albumid)));
    }
    const ft = $("home-featured");
    if (ft && d.playlists) {
      const pls = d.playlists.filter(p => p.id);
      ft.innerHTML = pls.map(p =>
        `<div class="card" data-saavnpl="${esc(p.id)}">
           <div class="card-img">${p.image?`<img src="${esc(p.image)}" alt="">`:'<div class="card-img-ph">🎵</div>'}</div>
           <div class="card-title">${esc(p.title)}</div>
         </div>`).join("");
      ft.querySelectorAll(".card[data-saavnpl]").forEach(el =>
        el.addEventListener("click", () => openSaavnPlaylist(el.dataset.saavnpl)));
    }
  } catch (e) { console.error("home shelves", e); }
}

async function openSaavnPlaylist(id) {
  if (!id) { toast("Playlist unavailable"); return; }
  const list = $("chart-list"), title = $("chart-title");
  title.textContent = "Loading…";
  list.innerHTML = `<div class="state-msg"><div class="spinner"></div><p>Loading playlist…</p></div>`;
  goto("home");
  try {
    const r = await fetch(`/api/playlist/${id}`);
    const d = await r.json();
    if (!d.songs?.length) throw new Error("empty");
    title.textContent = d.title || "Playlist";
    S.queue = d.songs; S.queueIdx = -1;
    list.innerHTML = d.songs.map((t,i) => trackHTML(t,i,true)).join("");
    bind(list, d.songs);
  } catch { list.innerHTML = `<div class="state-msg"><p>Couldn't load playlist.</p></div>`; }
}

/* ── Settings sheet ──────────────────────────────────────────────────────── */
function openHomeSettings() {
  const list = $("settings-shelf-list");
  list.innerHTML = SHELF_DEFS.map(def => {
    const on = S.homeCfg.shelves.includes(def.id);
    return `<label class="settings-toggle">
      <span>${def.label}</span>
      <input type="checkbox" data-shelf="${def.id}" ${on?"checked":""}>
    </label>`;
  }).join("");
  list.querySelectorAll("input[data-shelf]").forEach(cb =>
    cb.addEventListener("change", () => {
      const id = cb.dataset.shelf;
      if (cb.checked) {
        if (!S.homeCfg.shelves.includes(id)) S.homeCfg.shelves.push(id);
      } else {
        S.homeCfg.shelves = S.homeCfg.shelves.filter(s => s !== id);
      }
      LS.set("nd_homecfg", S.homeCfg);
      applyShelfVisibility();
    }));
  // Theme picker
  const seg = $("theme-seg");
  if (seg) {
    const sync = () => seg.querySelectorAll(".seg-opt").forEach(b =>
      b.classList.toggle("active", b.dataset.themeOpt === S.theme));
    sync();
    seg.querySelectorAll(".seg-opt").forEach(b => {
      b.onclick = () => { setTheme(b.dataset.themeOpt); sync(); };
    });
  }
  const sel = $("settings-default-lang");
  if (sel) {
    sel.value = S.homeCfg.defaultLang || "telugu";
    sel.onchange = () => { S.homeCfg.defaultLang = sel.value; LS.set("nd_homecfg", S.homeCfg); };
  }
  // AI anecdote toggle
  const aiTog  = $("settings-ai-anecdote");
  const aiHint = $("settings-ai-hint");
  if (aiTog) {
    aiTog.checked  = S.aiAnecdoteOn;
    aiTog.disabled = !S.aiEnabled;
    aiTog.onchange = () => { S.aiAnecdoteOn = aiTog.checked; LS.set("nd_ai_anecdote", S.aiAnecdoteOn); };
  }
  if (aiHint) {
    aiHint.textContent = S.aiEnabled
      ? "Uses your Gemini key. Stories are cached so each song is fetched once."
      : "Add your Gemini API key to config.json to enable AI features.";
  }
  $("home-settings-sheet").classList.add("open");
}
$("btn-home-settings").addEventListener("click", openHomeSettings);
$("home-settings-close").addEventListener("click", () => $("home-settings-sheet").classList.remove("open"));

// About sheet
const aboutOpen = $("about-open");
if (aboutOpen) aboutOpen.addEventListener("click", () => {
  $("home-settings-sheet").classList.remove("open");
  $("about-sheet").classList.add("open");
  if (typeof renderInstallState === "function") renderInstallState();
});
const aboutClose = $("about-close");
if (aboutClose) aboutClose.addEventListener("click", () => $("about-sheet").classList.remove("open"));

/* ── Mini-player ↔ Now-screen ────────────────────────────────────────────── */
function openNowScreen() {
  if (!S.track) return;
  $("now-screen").classList.add("open");
}
function closeNowScreen() { $("now-screen").classList.remove("open"); }

$("mini-player").addEventListener("click", e => {
  // Ignore clicks on the inline buttons
  if (e.target.closest(".mini-btn")) return;
  openNowScreen();
});
$("mini-player").addEventListener("keydown", e => {
  if (e.key === "Enter" || e.key === " ") { e.preventDefault(); openNowScreen(); }
});
$("now-close").addEventListener("click", closeNowScreen);
$("now-queue-btn").addEventListener("click", () => { closeNowScreen(); goto("queue"); });

$("mini-play").addEventListener("click", e => { e.stopPropagation(); togglePlay(); });
$("mini-next").addEventListener("click", e => { e.stopPropagation(); playNext(); });
$("mini-like").addEventListener("click", e => { e.stopPropagation(); if (S.track) toggleLike(S.track.id, [S.track]); });

// Now-screen extra actions
const npRadio = $("np-radio");
if (npRadio) npRadio.addEventListener("click", async () => {
  if (!S.track) return;
  toast("Building radio…");
  try {
    const r = await fetch(`/api/radio?song_id=${S.track.id}&lang=${S.homeLang}`);
    const d = await r.json();
    if (d.songs?.length) {
      S.queue = [S.track, ...d.songs];
      S.queueIdx = 0;
      toast(`Radio: ${d.songs.length} songs queued`);
      renderQueueEditable();
    } else { toast("No radio available"); }
  } catch { toast("Radio failed"); }
});
const npLyr = $("np-lyrics");
if (npLyr) npLyr.addEventListener("click", openLyrics);

/* ── Queue editing (reorder, remove, play-next) ──────────────────────────── */
function renderQueueEditable() {
  const el = $("queue-list");
  const actions = $("queue-actions");
  if (!S.queue.length) {
    el.innerHTML = `<div class="state-msg"><i class="ti ti-list"></i><p>Queue is empty</p></div>`;
    if (actions) actions.style.display = "none";
    return;
  }
  if (actions) actions.style.display = "flex";
  el.innerHTML = S.queue.map((t,i) =>
    `<div class="track q-row${S.track?.id===t.id?" now":""}" draggable="true" data-idx="${i}" data-id="${esc(t.id)}">
       <span class="q-grip" aria-label="Drag to reorder"><i class="ti ti-grip-vertical"></i></span>
       <div class="t-img">${t.image?`<img src="${esc(t.image)}" alt="" loading="lazy">`:'<span class="t-img-ph">🎵</span>'}</div>
       <div class="t-info"><div class="t-title">${esc(t.title)}</div><div class="t-meta">${esc(t.artist||"")}</div></div>
       <button class="q-remove" data-rm="${i}" aria-label="Remove"><i class="ti ti-x"></i></button>
     </div>`).join("");

  // Tap row (not grip/remove) to play
  el.querySelectorAll(".q-row").forEach(row => {
    row.addEventListener("click", e => {
      if (e.target.closest(".q-remove") || e.target.closest(".q-grip")) return;
      const i = parseInt(row.dataset.idx);
      if (!isNaN(i) && S.queue[i]) play(S.queue[i], S.queue, i);
    });
  });
  // Remove
  el.querySelectorAll(".q-remove").forEach(btn => {
    btn.addEventListener("click", e => {
      e.stopPropagation();
      const i = parseInt(btn.dataset.rm);
      if (isNaN(i)) return;
      const wasCurrent = S.queue[i]?.id === S.track?.id;
      S.queue.splice(i,1);
      if (i < S.queueIdx) S.queueIdx--;
      else if (wasCurrent) S.queueIdx = Math.min(S.queueIdx, S.queue.length-1);
      renderQueueEditable();
      $("queue-badge").textContent = `${S.queue.length} song${S.queue.length!==1?"s":""}`;
    });
  });
  // Drag to reorder
  let dragIdx = null;
  el.querySelectorAll(".q-row").forEach(row => {
    row.addEventListener("dragstart", () => { dragIdx = parseInt(row.dataset.idx); row.classList.add("dragging"); });
    row.addEventListener("dragend",   () => { row.classList.remove("dragging"); dragIdx = null; });
    row.addEventListener("dragover",  e => { e.preventDefault(); row.classList.add("drag-over"); });
    row.addEventListener("dragleave", () => row.classList.remove("drag-over"));
    row.addEventListener("drop", e => {
      e.preventDefault();
      row.classList.remove("drag-over");
      const dropIdx = parseInt(row.dataset.idx);
      if (dragIdx === null || dragIdx === dropIdx) return;
      const [moved] = S.queue.splice(dragIdx, 1);
      S.queue.splice(dropIdx, 0, moved);
      // keep queueIdx pointing at the currently-playing track
      S.queueIdx = S.queue.findIndex(t => t.id === S.track?.id);
      renderQueueEditable();
    });
  });
}

const qClear = $("queue-clear");
if (qClear) qClear.addEventListener("click", () => {
  // Keep the currently playing track, clear the rest
  if (S.track) { S.queue = [S.track]; S.queueIdx = 0; }
  else { S.queue = []; S.queueIdx = -1; }
  renderQueueEditable();
  $("queue-badge").textContent = `${S.queue.length} song${S.queue.length!==1?"s":""}`;
  toast("Queue cleared");
});

/* ── Playlist detail editing (remove songs, rename, delete) ──────────────── */
S.plEditMode = false;

function renderPlaylistDetail(pl) {
  const list = $("pl-detail-list");
  if (!pl.songs.length) {
    list.innerHTML = `<div class="state-msg"><i class="ti ti-playlist"></i><p>No songs yet.<br>Add songs from the now-playing screen.</p></div>`;
    return;
  }
  const sel = S.plSel || new Set();
  list.innerHTML = pl.songs.map((t,i) =>
    `<div class="track pl-row${S.track?.id===t.id?" now":""}${S.plEditMode && sel.has(t.id)?" selected":""}" data-idx="${i}" data-id="${esc(t.id)}">
       ${S.plEditMode
         ? `<span class="pl-grip" aria-label="Drag to reorder"><i class="ti ti-grip-vertical"></i></span>
            <span class="sel-box"><i class="ti ti-check"></i></span>`
         : `<span class="t-num">${i+1}</span>`}
       <div class="t-img">${t.image?`<img src="${esc(t.image)}" alt="" loading="lazy">`:'<span class="t-img-ph">🎵</span>'}</div>
       <div class="t-info"><div class="t-title">${esc(t.title)}</div><div class="t-meta">${esc(t.artist||"")}</div></div>
       <div class="t-right">
         <span class="t-dur">${fmt(t.duration)}</span>
         ${S.plEditMode ? "" : `<button class="t-menu" data-menu="${esc(t.id)}" aria-label="More options"><i class="ti ti-dots-vertical"></i></button>`}
       </div>
     </div>`).join("");

  list.querySelectorAll(".pl-row").forEach(row => {
    row.addEventListener("click", e => {
      if (e.target.closest(".pl-grip")) return;   // grip is for dragging
      if (e.target.closest(".t-menu")) return;    // handled by the menu binding
      const i = parseInt(row.dataset.idx);
      if (isNaN(i) || !pl.songs[i]) return;
      if (S.plEditMode) {
        // Edit mode: tapping toggles selection instead of playing
        const id = pl.songs[i].id;
        if (sel.has(id)) sel.delete(id); else sel.add(id);
        S.plSel = sel;
        row.classList.toggle("selected", sel.has(id));
        updateBulkBar();
      } else {
        play(pl.songs[i], pl.songs, i);
      }
    });
  });

  // Reordering is available in Select mode (see initPlaylistReorder)
  if (typeof initPlaylistReorder === "function") initPlaylistReorder(pl);
}

/* ── Bulk bar: shown while songs are selected in playlist edit mode ─────── */
function updateBulkBar() {
  const bar = $("bulk-bar");
  const n = S.plSel ? S.plSel.size : 0;
  $("bulk-count").textContent = `${n} selected`;
  bar.classList.toggle("show", S.plEditMode && n > 0);
}

function bulkSelectedTracks() {
  const pl = S.playlists.find(p => p.id === S.viewPlId);
  if (!pl || !S.plSel) return [];
  return pl.songs.filter(s => S.plSel.has(s.id));
}

$("bulk-remove").addEventListener("click", () => {
  const pl = S.playlists.find(p => p.id === S.viewPlId);
  if (!pl || !S.plSel?.size) return;
  const n = S.plSel.size;
  pl.songs = pl.songs.filter(s => !S.plSel.has(s.id));
  S.plSel.clear();
  save(); renderPlaylistDetail(pl); renderHomeLibraryShelves(); updateBulkBar();
  toast(`Removed ${n} song${n!==1?"s":""}`);
});

$("bulk-copy").addEventListener("click", () => {
  const tracks = bulkSelectedTracks();
  if (!tracks.length) return;
  openAddToPlaylist({ bulk: tracks, mode: "copy" });
});

$("bulk-move").addEventListener("click", () => {
  const tracks = bulkSelectedTracks();
  if (!tracks.length) return;
  openAddToPlaylist({ bulk: tracks, mode: "move" });
});

const plEditToggle = $("pl-edit-toggle");
if (plEditToggle) plEditToggle.addEventListener("click", () => {
  S.plEditMode = !S.plEditMode;
  S.plSel = new Set();
  plEditToggle.textContent = S.plEditMode ? "Done" : "Select";
  const hint = $("pl-reorder-hint");
  if (hint) hint.style.display = S.plEditMode ? "flex" : "none";
  const pl = S.playlists.find(p => p.id === S.viewPlId);
  if (pl) renderPlaylistDetail(pl);
  updateBulkBar();
});

// ⋮ on the playlist detail sheet opens the proper action sheet
const plMenu = $("pl-detail-menu");
if (plMenu) plMenu.addEventListener("click", () => {
  if (S.viewPlId) openPlMenu(S.viewPlId);
});

// New-playlist button inside add-to-playlist sheet
const addPlNew = $("add-pl-new");
if (addPlNew) addPlNew.addEventListener("click", () => {
  $("add-pl-sheet").classList.remove("open");
  S.newPlWithTracks = true;   // route the create-confirm into the add flow
  $("create-pl-modal").classList.add("open");
  setTimeout(() => $("pl-name-input")?.focus(), 100);
});

// Capture-phase: when creating from the add-to-playlist flow, the new
// playlist is born already containing the pending track(s)/selection.
$("pl-create-confirm").addEventListener("click", e => {
  if (!S.newPlWithTracks || S.renamePlId) return;
  e.stopImmediatePropagation();
  S.newPlWithTracks = false;
  const name = $("pl-name-input").value.trim();
  if (!name) return;
  const pl = { id: uid(), name, songs: [] };
  S.playlists.unshift(pl);
  $("create-pl-modal").classList.remove("open");
  if (S._addTracksTo) S._addTracksTo(pl);
  else { save(); renderPlaylists(); toast(`Playlist "${name}" created`); }
}, true);

/* ── Home see-all shortcuts ──────────────────────────────────────────────── */
const homeLikedMore = $("home-liked-more");
if (homeLikedMore) homeLikedMore.addEventListener("click", () => goto("library"));
const homePlMore = $("home-pl-more");
if (homePlMore) homePlMore.addEventListener("click", () => goto("library"));


/* ═══════════════════════════════════════════════════════════════════════
   AI features — anecdote card + trivia (Gemini, via our backend)
═══════════════════════════════════════════════════════════════════════ */
S.aiEnabled    = false;   // set from /api/ai/status
S.aiAnecdoteOn = LS.get("nd_ai_anecdote", true);   // user toggle (default on)

async function checkAiStatus() {
  try {
    const r = await fetch("/api/ai/status");
    const d = await r.json();
    S.aiEnabled = !!d.enabled;
  } catch { S.aiEnabled = false; }
  // Hide AI-dependent UI if no key configured
  const story = $("np-story");
  if (story) story.style.display = S.aiEnabled ? "" : "none";
}

/* Debounce: only fetch a story after the song has played for a few seconds.
   Skipping before then cancels it — so rapid skipping costs zero AI calls. */
let _anecdoteTimer = null;
const ANECDOTE_DELAY = 10000;   // 10s of real listening before we fetch
function scheduleAnecdote(track) {
  const card = $("ai-anecdote");
  if (_anecdoteTimer) { clearTimeout(_anecdoteTimer); _anecdoteTimer = null; }
  if (!S.aiEnabled || !S.aiAnecdoteOn || !track) {
    if (card) card.style.display = "none";
    return;
  }
  // Show a gentle placeholder, but don't call the API until the timer fires
  if (card) {
    card.style.display = "block";
    const body = $("ai-anecdote-body");
    if (body) body.innerHTML = `<div class="ai-loading">A story about this track will appear as you listen…</div>`;
  }
  _anecdoteTimer = setTimeout(() => {
    // Only fetch if this is still the current track (user didn't skip)
    if (S.track && S.track.id === track.id) loadAnecdote(track);
  }, ANECDOTE_DELAY);
}

async function loadAnecdote(track) {
  const card = $("ai-anecdote");
  const body = $("ai-anecdote-body");
  if (!card || !body) return;
  if (!S.aiEnabled || !S.aiAnecdoteOn || !track) { card.style.display = "none"; return; }

  card.style.display = "block";
  body.innerHTML = `<div class="ai-loading"><div class="spinner"></div> Finding something interesting…</div>`;
  try {
    const qs = new URLSearchParams({
      title: track.title || "", artist: track.artist || "", album: track.album || ""
    });
    const r = await fetch(`/api/ai/anecdote?${qs}`);
    const d = await r.json();
    if (d.text) {
      body.textContent = d.text;
    } else if (d.error === "cooldown") {
      body.innerHTML = `<div class="ai-loading">✨ Taking a brief breather — stories will return shortly.</div>`;
    } else {
      card.style.display = "none";   // nothing interesting / disabled
    }
  } catch {
    card.style.display = "none";
  }
}

// Story button: force-open the anecdote even if auto is off
const npStory = $("np-story");
if (npStory) npStory.addEventListener("click", () => {
  if (!S.aiEnabled) { toast("Add your Gemini key in config.json to enable AI"); return; }
  const prev = S.aiAnecdoteOn;
  S.aiAnecdoteOn = true;
  loadAnecdote(S.track).then(() => { S.aiAnecdoteOn = prev; });
});

/* ── Trivia tab ──────────────────────────────────────────────────────────── */
async function loadTrivia(force=false) {
  const list = $("trivia-list");
  if (!list) return;
  if (!S.aiEnabled) {
    list.innerHTML = `<div class="state-msg"><i class="ti ti-sparkles"></i><p>Add your Gemini API key to<br>config.json to enable trivia</p></div>`;
    return;
  }
  // Build from recent history (unique songs)
  const seen = new Set();
  const songs = [];
  for (const t of S.history) {
    if (t.id && !seen.has(t.id)) { seen.add(t.id); songs.push({id:t.id, title:t.title, artist:t.artist, album:t.album}); }
    if (songs.length >= 8) break;
  }
  if (!songs.length) {
    list.innerHTML = `<div class="state-msg"><i class="ti ti-bulb"></i><p>Play a few songs first,<br>then come back for trivia</p></div>`;
    return;
  }
  list.innerHTML = `<div class="state-msg"><div class="spinner"></div><p>Generating trivia…</p></div>`;
  try {
    const r = await fetch("/api/ai/trivia", {
      method: "POST",
      headers: {"Content-Type":"application/json"},
      body: JSON.stringify({songs})
    });
    const d = await r.json();
    const cards = d.cards || [];
    if (!cards.length) {
      list.innerHTML = `<div class="state-msg"><i class="ti ti-mood-empty"></i><p>Couldn't generate trivia.<br>Try again in a moment.</p></div>`;
      return;
    }
    list.innerHTML = cards.map((c,i) =>
      `<div class="trivia-card" data-i="${i}">
         <div class="trivia-q"><span class="trivia-badge">Q</span>${esc(c.question)}</div>
         <div class="trivia-a" id="trivia-a-${i}" style="display:none">${esc(c.answer)}</div>
         <button class="trivia-reveal" data-i="${i}">Reveal answer</button>
         ${c.song ? `<div class="trivia-song"><i class="ti ti-music"></i> ${esc(c.song)}</div>` : ""}
       </div>`).join("");
    list.querySelectorAll(".trivia-reveal").forEach(btn =>
      btn.addEventListener("click", () => {
        const a = $(`trivia-a-${btn.dataset.i}`);
        if (a) { a.style.display = "block"; btn.style.display = "none"; }
      }));
  } catch {
    list.innerHTML = `<div class="state-msg"><i class="ti ti-wifi-off"></i><p>Trivia unavailable right now</p></div>`;
  }
}


/* ═══════════════════════════════════════════════════════════════════════
   Backup & restore — export/import all user data as a JSON file.
   Survives cache clears, uninstalls, and device changes. The keys below are
   the complete set of user-owned data (library, prefs, layout).
═══════════════════════════════════════════════════════════════════════ */
const BACKUP_KEYS = [
  "nd_liked", "nd_pls", "nd_history", "nd_homecfg",
  "nd_shuffle", "nd_repeat", "nd_ai_anecdote",
  "nd_shelf_touch", "nd_collapsed",
  "nd_pinned", "nd_theme", "nd_folders",
  "nd_pod_subs", "nd_ep_progress", "nd_speed", "nd_playcounts",
  "nd_recent_searches", "nd_resume",
];

function exportData() {
  const data = {};
  for (const k of BACKUP_KEYS) {
    const v = localStorage.getItem(k);
    if (v !== null) data[k] = v;   // store raw JSON strings as-is
  }
  const payload = {
    app: "Naada",
    version: 1,
    exportedAt: new Date().toISOString(),
    counts: {
      liked: (LS.get("nd_liked", []) || []).length,
      playlists: (LS.get("nd_pls", []) || []).length,
      history: (LS.get("nd_history", []) || []).length,
    },
    data,
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  const stamp = new Date().toISOString().slice(0,10);
  a.href = url;
  a.download = `naada-backup-${stamp}.json`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  toast("Backup downloaded");
}

function importData(file) {
  const reader = new FileReader();
  reader.onload = () => {
    let parsed;
    try { parsed = JSON.parse(reader.result); }
    catch { toast("That file isn't a valid backup"); return; }
    const data = parsed && parsed.data;
    if (!data || typeof data !== "object" || !data.nd_pls && !data.nd_liked) {
      toast("That doesn't look like a Naada backup"); return;
    }
    const c = parsed.counts || {};
    const msg = `Restore this backup?\n\n` +
      `• ${c.playlists ?? "?"} playlists\n` +
      `• ${c.liked ?? "?"} liked songs\n` +
      `• ${c.history ?? "?"} recently played\n\n` +
      `This replaces your current data on this device.`;
    if (!confirm(msg)) return;
    // Write each key back exactly as stored
    for (const k of BACKUP_KEYS) {
      if (k in data && typeof data[k] === "string") {
        try { localStorage.setItem(k, data[k]); } catch {}
      }
    }
    toast("Backup restored — reloading…");
    setTimeout(() => location.reload(), 900);
  };
  reader.onerror = () => toast("Couldn't read that file");
  reader.readAsText(file);
}

// Wire backup/restore buttons (in the About sheet)
const backupBtn = $("backup-btn");
if (backupBtn) backupBtn.addEventListener("click", exportData);
const restoreBtn = $("restore-btn");
const restoreInput = $("restore-input");
if (restoreBtn && restoreInput) {
  restoreBtn.addEventListener("click", () => restoreInput.click());
  restoreInput.addEventListener("change", e => {
    const file = e.target.files && e.target.files[0];
    if (file) importData(file);
    e.target.value = "";   // allow re-selecting the same file later
  });
}

/* ── In-app updater ──────────────────────────────────────────────────────
   Tells the service worker to fetch the latest files and reload — replaces
   the old "clear your cache" ritual. Never touches localStorage, so user
   data is always preserved across updates. */
async function checkForUpdate() {
  const btn = $("update-btn");
  if (btn) { btn.disabled = true; btn.textContent = "Checking…"; }
  try {
    if ("serviceWorker" in navigator) {
      const reg = await navigator.serviceWorker.getRegistration();
      if (reg) {
        await reg.update();   // ask the browser to re-check sw.js
      }
    }
    // Bust the app-shell caches we control, then hard reload from network.
    if ("caches" in window) {
      const keys = await caches.keys();
      await Promise.all(keys.map(k => caches.delete(k)));
    }
    toast("Updating to the latest version…");
    setTimeout(() => location.reload(true), 700);
  } catch {
    toast("Couldn't check for updates");
    if (btn) { btn.disabled = false; btn.textContent = "Update now"; }
  }
}
const updateBtn = $("update-btn");
if (updateBtn) updateBtn.addEventListener("click", checkForUpdate);


const triviaRefresh = $("trivia-refresh");
if (triviaRefresh) triviaRefresh.addEventListener("click", () => loadTrivia(true));

/* ── Init ────────────────────────────────────────────────────────────────── */
(function bootstrap(){
  // Apply saved home customization
  applyShelfVisibility();
  applyCollapsed();
  const dl = S.homeCfg.defaultLang || "telugu";
  S.homeLang = dl;

  initHome();
  initSearch();
  checkAiStatus();
  $("greeting").textContent = greeting();

  // Reflect saved language in the pill + popover
  const lbl = $("lang-pill-label");
  if (lbl) lbl.textContent = LANG_LABELS[dl] || dl;
  const gly = $("lang-pill-glyph");
  if (gly) gly.textContent = LANG_GLYPHS[dl] || "త";
  $$(".lang-opt").forEach(o => o.classList.toggle("active", o.dataset.lang === dl));

  // Show app build in the About sheet
  const build = $("app-build");
  if (build) build.textContent = `Naada ${APP_VERSION} · adabala`;

  // Load content for default language
  loadCharts(dl);
  loadNewReleases(dl);
  loadHomeShelves(dl);
})();

/* ═══════════════════════════════════════════════════════════════════════
   v23: Track context menu (long-press), pins, playlist action sheet,
        bulk move/copy, queue back nav, audio-state sync
═══════════════════════════════════════════════════════════════════════ */

/* ── Pin helpers ─────────────────────────────────────────────────────────── */
function isPinned(id) { return S.pinned.some(t => t.id === id); }

function togglePin(track) {
  if (!track) return;
  if (isPinned(track.id)) {
    S.pinned = S.pinned.filter(t => t.id !== track.id);
    toast("Unpinned");
  } else {
    S.pinned = [{...track}, ...S.pinned].slice(0, 24);
    toast("Pinned to Home");
    touchShelf("pinned");
  }
  save();
  renderHomeLibraryShelves();
  applyShelfVisibility();
}

/* ── Queue helpers ───────────────────────────────────────────────────────── */
function queuePlayNext(track) {
  if (!track) return;
  // Remove any existing copy, then insert right after the current song
  S.queue = S.queue.filter(t => t.id !== track.id);
  const at = Math.min(S.queueIdx + 1, S.queue.length);
  S.queue.splice(at, 0, {...track});
  S.queueIdx = S.queue.findIndex(t => t.id === S.track?.id);
  toast(`Playing next: ${track.title}`);
  if (S.page === "queue") renderQueueEditable();
}

function queueAddLast(track) {
  if (!track) return;
  if (S.queue.some(t => t.id === track.id)) { toast("Already in queue"); return; }
  S.queue.push({...track});
  toast("Added to queue");
  if (S.page === "queue") renderQueueEditable();
}

/* ── Track context menu ──────────────────────────────────────────────────
   Long-press any song row, anywhere in the app, for a contextual action
   sheet. What "Remove" means depends on where you pressed:
   liked list → unlike · history → remove from history · playlist → remove
   from that playlist · queue → remove from queue. */
const trackMenu = $("track-menu");

function tmContextOf(row) {
  // Walk up from the row to figure out which list it lives in
  if (row.closest("#liked-list"))     return "liked";
  if (row.closest("#home-liked"))     return "liked";
  if (row.closest("#history-list"))   return "history";
  if (row.closest("#home-recents"))   return "history";
  if (row.closest("#pl-detail-list")) return "playlist";
  if (row.closest("#queue-list"))     return "queue";
  if (row.closest("#home-pinned"))    return "pinned";
  return "browse";
}

function tmFindTrack(id, ctx) {
  const pools = {
    liked:    [S.liked],
    history:  [S.history],
    pinned:   [S.pinned],
    queue:    [S.queue],
    playlist: [S.playlists.find(p => p.id === S.viewPlId)?.songs || []],
    browse:   [S.queue, S.history, S.liked],
  }[ctx] || [];
  for (const pool of pools) {
    const t = pool.find(x => x.id === id);
    if (t) return t;
  }
  // Fall back to any rendered list registry entry
  for (const el of document.querySelectorAll("[id]")) {
    const list = _listRegistry.get(el);
    const t = list && list.find(x => x.id === id);
    if (t) return t;
  }
  return null;
}

function openTrackMenu(track, ctx) {
  if (!track) return;
  S.tmTrack = track; S.tmCtx = ctx;

  // Header
  $("tm-title").textContent  = track.title  || "—";
  $("tm-artist").textContent = track.artist || "";
  $("tm-art").innerHTML = track.image
    ? `<img src="${esc(track.image)}" alt="">` : "🎵";

  const liked  = isLiked(track.id);
  const pinned = isPinned(track.id);
  const removeLabel = {
    liked:    { icon: "ti-heart-off", text: "Remove from Liked" },
    history:  { icon: "ti-eraser",    text: "Remove from history" },
    playlist: { icon: "ti-playlist-x",text: "Remove from this playlist" },
    queue:    { icon: "ti-list-details", text: "Remove from queue" },
    pinned:   null,   // pin toggle already covers it
    browse:   null,
  }[ctx];

  const opts = [
    { act:"playnext", icon:"ti-player-track-next", text:"Play next" },
    { act:"addqueue", icon:"ti-playlist-add",      text:"Add to queue" },
    { act:"addpl",    icon:"ti-folder-plus",       text:"Add to playlist…" },
    { act:"like",     icon: liked ? "ti-heart-off" : "ti-heart",
                      text: liked ? "Unlike" : "Like" },
    { act:"pin",      icon: pinned ? "ti-pin-off" : "ti-pin",
                      text: pinned ? "Unpin from Home" : "Pin to Home" },
    { act:"radio",    icon:"ti-radio",             text:"Start radio from this" },
  ];
  if (removeLabel) opts.push({ act:"remove", icon:removeLabel.icon, text:removeLabel.text, danger:true });

  $("tm-opts").innerHTML = opts.map(o =>
    `<div class="modal-opt${o.danger?" danger":""}" data-act="${o.act}">
       <i class="ti ${o.icon}"></i>${o.text}
     </div>`).join("");

  trackMenu.classList.add("open");
}

function closeTrackMenu() { trackMenu.classList.remove("open"); }
$("tm-cancel").addEventListener("click", closeTrackMenu);
trackMenu.addEventListener("click", e => { if (e.target === trackMenu) closeTrackMenu(); });

$("tm-opts").addEventListener("click", async e => {
  const opt = e.target.closest(".modal-opt[data-act]");
  if (!opt) return;
  const t = S.tmTrack, ctx = S.tmCtx;
  closeTrackMenu();
  if (!t) return;

  switch (opt.dataset.act) {
    case "playnext": queuePlayNext(t); break;
    case "addqueue": queueAddLast(t); break;
    case "addpl":
      S.addPlTrack = t;
      openAddToPlaylist();
      break;
    case "like": toggleLike(t.id, [t]); break;
    case "pin":  togglePin(t); break;
    case "radio":
      toast("Building radio…");
      try {
        const r = await fetch(`/api/radio?song_id=${t.id}&lang=${S.homeLang}`);
        const d = await r.json();
        if (d.songs?.length) {
          S.queue = [t, ...d.songs]; S.queueIdx = 0;
          play(t, S.queue, 0);
        } else toast("No radio available");
      } catch { toast("Radio failed"); }
      break;
    case "remove":
      if (ctx === "liked") {
        S.liked = S.liked.filter(x => x.id !== t.id);
        save(); patchLists(); updateNpLike();
        if (S.page === "library") renderLibrary();
        renderHomeLibraryShelves(); toast("Removed from Liked");
      } else if (ctx === "history") {
        S.history = S.history.filter(x => x.id !== t.id);
        save();
        if (S.page === "library") renderLibrary();
        renderHomeLibraryShelves(); toast("Removed from history");
      } else if (ctx === "playlist") {
        const pl = S.playlists.find(p => p.id === S.viewPlId);
        if (pl) {
          pl.songs = pl.songs.filter(x => x.id !== t.id);
          save(); renderPlaylistDetail(pl); renderHomeLibraryShelves();
          toast(`Removed from "${pl.name}"`);
        }
      } else if (ctx === "queue") {
        const i = S.queue.findIndex(x => x.id === t.id);
        if (i !== -1) {
          S.queue.splice(i, 1);
          if (i < S.queueIdx) S.queueIdx--;
          if (S.page === "queue") renderQueueEditable();
          toast("Removed from queue");
        }
      }
      break;
  }
});

/* ── Long-press detection (touch + mouse), delegated on document ─────────── */
(function initLongPress() {
  const HOLD_MS = 480, MOVE_TOL = 12;
  let timer = null, startX = 0, startY = 0, row = null, fired = false;

  function clear() {
    clearTimeout(timer); timer = null;
    if (row) row.classList.remove("pressing");
    row = null;
  }

  document.addEventListener("pointerdown", e => {
    const r = e.target.closest(".track[data-id], .mini-card[data-id]");
    if (!r) return;
    // Skip interactive children — like button, grip, remove
    if (e.target.closest("button, .q-grip")) return;
    row = r; fired = false;
    startX = e.clientX; startY = e.clientY;
    row.classList.add("pressing");
    timer = setTimeout(() => {
      fired = true;
      const ctx = tmContextOf(row);
      const track = tmFindTrack(row.dataset.id, ctx);
      if (track) {
        if (navigator.vibrate) { try { navigator.vibrate(12); } catch {} }
        openTrackMenu(track, ctx);
      }
      clear();
    }, HOLD_MS);
  }, { passive: true });

  document.addEventListener("pointermove", e => {
    if (!timer) return;
    if (Math.abs(e.clientX - startX) > MOVE_TOL || Math.abs(e.clientY - startY) > MOVE_TOL) clear();
  }, { passive: true });

  document.addEventListener("pointerup", clear, { passive: true });
  document.addEventListener("pointercancel", clear, { passive: true });

  // Swallow the click that follows a fired long-press so the song
  // doesn't also start playing underneath the menu
  document.addEventListener("click", e => {
    if (fired) { e.stopPropagation(); e.preventDefault(); fired = false; }
  }, true);

  // Desktop: right-click a row opens the same menu
  document.addEventListener("contextmenu", e => {
    const r = e.target.closest(".track[data-id], .mini-card[data-id]");
    if (!r) return;
    e.preventDefault();
    const ctx = tmContextOf(r);
    const track = tmFindTrack(r.dataset.id, ctx);
    if (track) openTrackMenu(track, ctx);
  });
})();

/* ── Playlist action sheet (replaces the old prompt()) ───────────────────── */
const plMenuSheet = $("pl-menu");

function openPlMenu(plid) {
  const pl = S.playlists.find(p => p.id === plid);
  if (!pl) return;
  S.plMenuId = plid;
  $("pl-menu-title").textContent = pl.name;
  $("pl-menu-opts").innerHTML = `
    <div class="modal-opt" data-plact="movefolder"><i class="ti ti-folder-symlink"></i>Move to folder…</div>
    <div class="modal-opt" data-plact="rename"><i class="ti ti-pencil"></i>Rename</div>
    <div class="modal-opt" data-plact="duplicate"><i class="ti ti-copy"></i>Duplicate</div>
    <div class="modal-opt" data-plact="pin"><i class="ti ti-pin${pl.pinned?"-off":""}"></i>${pl.pinned?"Unpin":"Pin to top"}</div>
    <div class="modal-opt" data-plact="playnext"><i class="ti ti-player-track-next"></i>Queue all songs</div>
    <div class="modal-opt danger" data-plact="delete"><i class="ti ti-trash"></i>Delete album</div>`;
  plMenuSheet.classList.add("open");
}
function closePlMenu() { plMenuSheet.classList.remove("open"); }
$("pl-menu-cancel").addEventListener("click", closePlMenu);
plMenuSheet.addEventListener("click", e => { if (e.target === plMenuSheet) closePlMenu(); });

$("pl-menu-opts").addEventListener("click", e => {
  const opt = e.target.closest(".modal-opt[data-plact]");
  if (!opt) return;
  const pl = S.playlists.find(p => p.id === S.plMenuId);
  closePlMenu();
  if (!pl) return;

  switch (opt.dataset.plact) {
    case "movefolder":
      if (typeof openFolderPicker === "function") openFolderPicker(pl.id);
      break;
    case "rename": {
      // Reuse the create-playlist modal in rename mode
      S.renamePlId = pl.id;
      $("create-pl-title").textContent = "Rename Playlist";
      $("pl-create-confirm").textContent = "Save";
      const inp = $("pl-name-input");
      inp.value = pl.name;
      $("create-pl-modal").classList.add("open");
      setTimeout(() => { inp.focus(); inp.select(); }, 100);
      break;
    }
    case "duplicate": {
      const copy = { id: uid(), name: `${pl.name} copy`, songs: pl.songs.map(s => ({...s})) };
      S.playlists.splice(S.playlists.indexOf(pl) + 1, 0, copy);
      save(); renderPlaylists(); renderHomeLibraryShelves();
      toast(`Duplicated as "${copy.name}"`);
      break;
    }
    case "pin": {
      pl.pinned = !pl.pinned;
      save(); renderPlaylists(); renderHomeLibraryShelves();
      toast(pl.pinned ? "Playlist pinned" : "Playlist unpinned");
      break;
    }
    case "playnext": {
      if (!pl.songs.length) { toast("Playlist is empty"); break; }
      for (const s of pl.songs) if (!S.queue.some(q => q.id === s.id)) S.queue.push({...s});
      S.queueIdx = S.queue.findIndex(t => t.id === S.track?.id);
      toast(`Queued ${pl.songs.length} songs`);
      break;
    }
    case "delete": {
      if (!confirm(`Delete "${pl.name}"? This can't be undone.`)) break;
      S.playlists = S.playlists.filter(p => p.id !== pl.id);
      // Drop it out of any folder too, so counts stay honest
      if (S.folders) {
        for (const f of S.folders) f.plIds = f.plIds.filter(id => id !== pl.id);
        saveFolders();
      }
      save();
      $("pl-detail-sheet").classList.remove("open");
      renderLibrary(); renderHomeLibraryShelves();
      toast(`"${pl.name}" deleted`);
      break;
    }
  }
});

/* Rename mode: hook into the existing create-confirm handler via capture,
   so the original "create" behaviour is skipped when we're renaming. */
$("pl-create-confirm").addEventListener("click", e => {
  if (!S.renamePlId) return;              // normal create flow
  e.stopImmediatePropagation();           // don't run the create handler
  const pl = S.playlists.find(p => p.id === S.renamePlId);
  const name = $("pl-name-input").value.trim();
  S.renamePlId = null;
  $("create-pl-title").textContent = "New Playlist";
  $("pl-create-confirm").textContent = "Create Playlist";
  if (pl && name) {
    pl.name = name;
    save();
    $("pl-detail-title").textContent = name;
    renderPlaylists(); renderHomeLibraryShelves();
    toast("Renamed");
  }
  $("create-pl-modal").classList.remove("open");
}, true);
$("pl-create-cancel").addEventListener("click", () => {
  S.renamePlId = null;
  $("create-pl-title").textContent = "New Playlist";
  $("pl-create-confirm").textContent = "Create Playlist";
});

/* ── Queue back button (queue left the nav bar) ──────────────────────────── */
const qBack = $("queue-back");
if (qBack) qBack.addEventListener("click", () => goto("home"));

/* ── Audio ↔ UI state sync ───────────────────────────────────────────────
   The OS can pause/resume audio without touching our buttons (phone call,
   audio-focus loss, media notification, bluetooth controls). Listening to
   the element itself keeps every play icon truthful. */
audio.addEventListener("play",  () => { S.playing = true;  setPlayIcons(true);  vinylPlay(true);  patchLists(); });
audio.addEventListener("pause", () => {
  // Ignore the pause that fires while switching tracks
  if (S.loading) return;
  S.playing = false; setPlayIcons(false); vinylPlay(false); patchLists();
});
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) {   // resync after returning from background
    S.playing = !audio.paused && !!audio.src;
    setPlayIcons(S.playing); vinylPlay(S.playing);
  }
});

/* ═══════════════════════════════════════════════════════════════════════
   v24: dynamic album colour, mini-player swipe, playlist drag-reorder
═══════════════════════════════════════════════════════════════════════ */

/* ── Dynamic colour from album art ───────────────────────────────────────
   Samples the artwork on a tiny offscreen canvas and derives two tones for
   the now-playing wash. Cached per image URL. Cross-origin art can taint
   the canvas — that throws, we catch, and the default gradient stays. */
const _artColorCache = new Map();

function _clampTone(r, g, b) {
  // Push very light / very dark samples toward a usable mid-tone so the
  // wash always reads against the text, in either theme.
  const lum = (0.299*r + 0.587*g + 0.114*b) / 255;
  let f = 1;
  if (lum > 0.72) f = 0.72 / lum;
  if (lum < 0.16) f = 1.5;
  return [r, g, b].map(v => Math.max(0, Math.min(255, Math.round(v * f))));
}

function extractArtColors(url) {
  if (!url) return Promise.resolve(null);
  if (_artColorCache.has(url)) return Promise.resolve(_artColorCache.get(url));

  return new Promise(resolve => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    const done = val => { _artColorCache.set(url, val); resolve(val); };
    img.onerror = () => done(null);
    img.onload = () => {
      try {
        const N = 16;                       // tiny sample grid — fast, plenty
        const c = document.createElement("canvas");
        c.width = c.height = N;
        const ctx = c.getContext("2d", { willReadFrequently: true });
        ctx.drawImage(img, 0, 0, N, N);
        const px = ctx.getImageData(0, 0, N, N).data;

        // Average the most saturated pixels — averaging everything gives mud
        let best = [], rs = 0, gs = 0, bs = 0, n = 0;
        for (let i = 0; i < px.length; i += 4) {
          const r = px[i], g = px[i+1], b = px[i+2];
          const mx = Math.max(r,g,b), mn = Math.min(r,g,b);
          best.push({ r, g, b, sat: mx - mn });
        }
        best.sort((a, b2) => b2.sat - a.sat);
        for (const p of best.slice(0, Math.max(6, best.length >> 2))) {
          rs += p.r; gs += p.g; bs += p.b; n++;
        }
        if (!n) return done(null);
        const [r, g, b] = _clampTone(rs/n, gs/n, bs/n);
        done({
          a: `rgba(${r},${g},${b},.55)`,
          b: `rgba(${r},${g},${b},.18)`,
          edge: `rgba(${r},${g},${b},.45)`,
        });
      } catch { done(null); }   // tainted canvas / CORS — fall back quietly
    };
    img.src = url;
  });
}

async function applyArtColors(track) {
  const now  = $("now-screen");
  const mini = $("mini-player");
  const clear = () => {
    now.classList.remove("tinted");
    mini.classList.remove("tinted");
  };
  if (!track?.image) { clear(); return; }

  const col = await extractArtColors(track.image);
  // The song may have changed while we were sampling — don't paint stale colour
  if (!col || S.track?.image !== track.image) { if (!col) clear(); return; }

  now.style.setProperty("--art-1", col.a);
  now.style.setProperty("--art-2", col.b);
  now.classList.add("tinted");
  mini.style.setProperty("--art-1", col.edge);
  mini.classList.add("tinted");
}

/* ── Mini-player swipe ───────────────────────────────────────────────────
   Swipe left → next, right → previous, up → open the full player.
   Horizontal intent is locked in before we start moving so a vertical
   page scroll that begins on the bar still scrolls the page. */
(function initMiniSwipe() {
  const bar = $("mini-player");
  if (!bar) return;

  const SKIP_PX = 68, OPEN_PX = 46, LOCK_PX = 10;
  let x0 = 0, y0 = 0, dx = 0, dy = 0;
  let active = false, axis = null, id = null;

  const reset = () => {
    bar.classList.remove("swiping", "hint-next", "hint-prev");
    bar.style.transform = "";
    bar.style.opacity = "";
    active = false; axis = null; id = null; dx = dy = 0;
  };

  bar.addEventListener("pointerdown", e => {
    if (e.target.closest(".mini-btn")) return;   // let the buttons work
    if (!S.track) return;
    active = true; axis = null; id = e.pointerId;
    x0 = e.clientX; y0 = e.clientY; dx = dy = 0;
  }, { passive: true });

  bar.addEventListener("pointermove", e => {
    if (!active || e.pointerId !== id) return;
    dx = e.clientX - x0;
    dy = e.clientY - y0;

    if (!axis) {
      if (Math.abs(dx) > LOCK_PX || Math.abs(dy) > LOCK_PX) {
        axis = Math.abs(dx) > Math.abs(dy) ? "x" : "y";
        if (axis === "x") { bar.classList.add("swiping"); bar.setPointerCapture(id); }
        else if (dy > 0) { reset(); return; }   // downward — let the page scroll
        else { bar.classList.add("swiping"); bar.setPointerCapture(id); }
      }
      return;
    }

    if (axis === "x") {
      bar.style.transform = `translateX(${dx * 0.85}px)`;
      bar.style.opacity = String(Math.max(0.45, 1 - Math.abs(dx) / 260));
      bar.classList.toggle("hint-next", dx < -SKIP_PX * 0.6);
      bar.classList.toggle("hint-prev", dx >  SKIP_PX * 0.6);
    } else {
      const up = Math.min(0, dy);
      bar.style.transform = `translateY(${up * 0.5}px)`;
    }
  }, { passive: true });

  function finish() {
    if (!active) return;
    const _dx = dx, _dy = dy, _axis = axis;
    bar.classList.add("settling");
    bar.classList.remove("swiping");

    if (_axis === "x" && Math.abs(_dx) > SKIP_PX) {
      // Fling the bar off, then swap the track and let it spring back
      bar.style.transform = `translateX(${_dx > 0 ? 340 : -340}px)`;
      bar.style.opacity = "0";
      setTimeout(() => {
        _dx > 0 ? playPrev() : playNext();
        bar.classList.remove("settling");
        bar.style.transform = `translateX(${_dx > 0 ? -60 : 60}px)`;
        bar.style.opacity = "0";
        requestAnimationFrame(() => {
          bar.classList.add("settling");
          bar.style.transform = "";
          bar.style.opacity = "";
        });
      }, 170);
    } else if (_axis === "y" && _dy < -OPEN_PX) {
      bar.style.transform = "";
      bar.style.opacity = "";
      openNowScreen();
    } else {
      bar.style.transform = "";
      bar.style.opacity = "";
    }

    setTimeout(() => bar.classList.remove("settling"), 320);
    active = false; axis = null; id = null;
    bar.classList.remove("hint-next", "hint-prev");
  }

  bar.addEventListener("pointerup", finish, { passive: true });
  bar.addEventListener("pointercancel", reset, { passive: true });

  // A swipe shouldn't also register as a tap that opens the player
  bar.addEventListener("click", e => {
    if (Math.abs(dx) > LOCK_PX || Math.abs(dy) > LOCK_PX) {
      e.stopImmediatePropagation(); e.preventDefault();
      dx = dy = 0;
    }
  }, true);
})();

/* ── Playlist drag-to-reorder ────────────────────────────────────────────
   Reordering is enabled in Select mode, where rows already aren't
   play-on-tap, so dragging can't fight with playback. */
function initPlaylistReorder(pl) {
  const list = $("pl-detail-list");
  if (!list || !S.plEditMode) return;
  let from = null;

  list.querySelectorAll(".pl-row").forEach(row => {
    row.setAttribute("draggable", "true");
    row.addEventListener("dragstart", e => {
      from = parseInt(row.dataset.idx);
      row.classList.add("dragging");
      try { e.dataTransfer.effectAllowed = "move"; } catch {}
    });
    row.addEventListener("dragend", () => { row.classList.remove("dragging"); from = null; });
    row.addEventListener("dragover", e => { e.preventDefault(); row.classList.add("drag-over"); });
    row.addEventListener("dragleave", () => row.classList.remove("drag-over"));
    row.addEventListener("drop", e => {
      e.preventDefault();
      row.classList.remove("drag-over");
      const to = parseInt(row.dataset.idx);
      if (from === null || isNaN(to) || from === to) return;
      const [moved] = pl.songs.splice(from, 1);
      pl.songs.splice(to, 0, moved);
      save();
      renderPlaylistDetail(pl);
      renderHomeLibraryShelves();
    });

    // Touch: long-press the grip then drag, mirroring the queue screen
    const grip = row.querySelector(".pl-grip");
    if (!grip) return;
    grip.addEventListener("pointerdown", ev => {
      ev.preventDefault();
      const startIdx = parseInt(row.dataset.idx);
      row.classList.add("dragging");
      const rows = [...list.querySelectorAll(".pl-row")];

      const onMove = m => {
        const el = document.elementFromPoint(m.clientX, m.clientY)?.closest(".pl-row");
        rows.forEach(r => r.classList.toggle("drag-over", r === el && r !== row));
      };
      const onUp = m => {
        document.removeEventListener("pointermove", onMove);
        document.removeEventListener("pointerup", onUp);
        row.classList.remove("dragging");
        const el = document.elementFromPoint(m.clientX, m.clientY)?.closest(".pl-row");
        rows.forEach(r => r.classList.remove("drag-over"));
        const to = el ? parseInt(el.dataset.idx) : NaN;
        if (!isNaN(to) && to !== startIdx) {
          const [moved] = pl.songs.splice(startIdx, 1);
          pl.songs.splice(to, 0, moved);
          save();
          renderPlaylistDetail(pl);
          renderHomeLibraryShelves();
        }
      };
      document.addEventListener("pointermove", onMove);
      document.addEventListener("pointerup", onUp);
    });
  });
}

/* ═══════════════════════════════════════════════════════════════════════
   v25: Folders — group albums/playlists, drag one onto a folder to file it
   Deliberately one level deep. Deep trees are where personal libraries go
   to die; a single shelf layer covers "all my Ram Charan albums together"
   without turning the Library into a file manager.
═══════════════════════════════════════════════════════════════════════ */

S.folders    = LS.get("nd_folders", []);   // [{id,name,plIds:[]}]
S.viewFolder = null;                        // null = root

function saveFolders() { LS.set("nd_folders", S.folders); }

function folderOf(plid) {
  return S.folders.find(f => f.plIds.includes(plid)) || null;
}

function movePlaylistToFolder(plid, folderId) {
  // A playlist lives in at most one folder — pull it out of any other first
  for (const f of S.folders) f.plIds = f.plIds.filter(id => id !== plid);
  if (folderId) {
    const f = S.folders.find(x => x.id === folderId);
    if (f && !f.plIds.includes(plid)) f.plIds.push(plid);
  }
  saveFolders();
}

function createFolder(name, withPlaylist = null) {
  const f = { id: uid(), name: name.trim(), plIds: [] };
  S.folders.unshift(f);
  if (withPlaylist) movePlaylistToFolder(withPlaylist, f.id);
  saveFolders();
  return f;
}

/* ── Render folders + the playlists for the current level ────────────── */
function renderFolders() {
  const grid = $("folder-grid");
  const crumb = $("pl-crumb");
  const title = $("pl-sec-title");
  if (!grid) return;

  if (S.viewFolder) {
    // Inside a folder: hide the folder grid, show the breadcrumb
    const f = S.folders.find(x => x.id === S.viewFolder);
    if (!f) { S.viewFolder = null; return renderFolders(); }
    grid.innerHTML = "";
    if (crumb) {
      crumb.style.display = "flex";
      $("crumb-name").textContent = f.name;
    }
    if (title) title.textContent = "In this folder";
    return;
  }

  if (crumb) crumb.style.display = "none";
  if (title) title.textContent = "Albums & Playlists";

  if (!S.folders.length) { grid.innerHTML = ""; return; }
  grid.innerHTML = S.folders.map(f => {
    const n = f.plIds.filter(id => S.playlists.some(p => p.id === id)).length;
    return `<div class="folder-card" data-folder="${esc(f.id)}">
      <div class="folder-ico"><i class="ti ti-folder-filled"></i></div>
      <div class="folder-meta">
        <div class="folder-name">${esc(f.name)}</div>
        <div class="folder-count">${n} album${n!==1?"s":""}</div>
      </div>
    </div>`;
  }).join("");

  grid.querySelectorAll(".folder-card").forEach(el => {
    el.addEventListener("click", () => {
      S.viewFolder = el.dataset.folder;
      renderFolders(); renderPlaylists();
    });
    // Long-press a folder for its options
    let t = null;
    el.addEventListener("pointerdown", () => {
      t = setTimeout(() => openFolderMenu(el.dataset.folder), 480);
    }, { passive: true });
    const cancel = () => { clearTimeout(t); t = null; };
    el.addEventListener("pointerup", cancel, { passive: true });
    el.addEventListener("pointermove", cancel, { passive: true });
    el.addEventListener("pointercancel", cancel, { passive: true });
  });
}

/* Which playlists show at the current level */
function visiblePlaylists() {
  if (S.viewFolder) {
    const f = S.folders.find(x => x.id === S.viewFolder);
    if (!f) return S.playlists;
    return S.playlists.filter(p => f.plIds.includes(p.id));
  }
  // Root shows only playlists that aren't filed in a folder
  const filed = new Set(S.folders.flatMap(f => f.plIds));
  return S.playlists.filter(p => !filed.has(p.id));
}

/* ── Breadcrumb controls ─────────────────────────────────────────────── */
const crumbBack = $("crumb-back");
if (crumbBack) crumbBack.addEventListener("click", () => {
  S.viewFolder = null; renderFolders(); renderPlaylists();
});
const crumbMenu = $("crumb-menu");
if (crumbMenu) crumbMenu.addEventListener("click", () => {
  if (S.viewFolder) openFolderMenu(S.viewFolder);
});

/* ── Folder action sheet ─────────────────────────────────────────────── */
function openFolderMenu(fid) {
  const f = S.folders.find(x => x.id === fid);
  if (!f) return;
  S.menuFolderId = fid;
  $("folder-menu-title").textContent = f.name;
  $("folder-menu-opts").innerHTML = `
    <div class="modal-opt" data-fact="open"><i class="ti ti-folder-open"></i>Open folder</div>
    <div class="modal-opt" data-fact="rename"><i class="ti ti-pencil"></i>Rename folder</div>
    <div class="modal-opt" data-fact="playall"><i class="ti ti-player-play"></i>Play everything inside</div>
    <div class="modal-opt danger" data-fact="delete"><i class="ti ti-folder-x"></i>Delete folder (keeps albums)</div>`;
  $("folder-menu").classList.add("open");
}
$("folder-menu-cancel").addEventListener("click", () => $("folder-menu").classList.remove("open"));
$("folder-menu").addEventListener("click", e => {
  if (e.target === $("folder-menu")) $("folder-menu").classList.remove("open");
});

$("folder-menu-opts").addEventListener("click", e => {
  const opt = e.target.closest(".modal-opt[data-fact]");
  if (!opt) return;
  const f = S.folders.find(x => x.id === S.menuFolderId);
  $("folder-menu").classList.remove("open");
  if (!f) return;

  switch (opt.dataset.fact) {
    case "open":
      S.viewFolder = f.id; renderFolders(); renderPlaylists();
      break;
    case "rename":
      S.renameFolderId = f.id;
      $("folder-name-title").textContent = "Rename Folder";
      $("folder-name-confirm").textContent = "Save";
      $("folder-name-input").value = f.name;
      $("folder-name-modal").classList.add("open");
      setTimeout(() => { const i = $("folder-name-input"); i.focus(); i.select(); }, 100);
      break;
    case "playall": {
      const songs = [];
      for (const id of f.plIds) {
        const pl = S.playlists.find(p => p.id === id);
        if (pl) for (const s of pl.songs) if (!songs.some(x => x.id === s.id)) songs.push({...s});
      }
      if (!songs.length) { toast("Nothing in this folder yet"); break; }
      S.queue = songs; S.queueIdx = 0;
      play(songs[0], songs, 0);
      toast(`Playing ${songs.length} songs from "${f.name}"`);
      break;
    }
    case "delete":
      // Deleting a folder never deletes music — the albums return to the top level
      if (!confirm(`Delete the folder "${f.name}"?\n\nThe albums inside stay in your library.`)) break;
      S.folders = S.folders.filter(x => x.id !== f.id);
      saveFolders();
      if (S.viewFolder === f.id) S.viewFolder = null;
      renderFolders(); renderPlaylists();
      toast(`Folder deleted — albums kept`);
      break;
  }
});

/* ── Create / rename folder modal ────────────────────────────────────── */
function openNewFolder(withPlaylist = null) {
  S.renameFolderId = null;
  S.newFolderFor = withPlaylist;
  $("folder-name-title").textContent = "New Folder";
  $("folder-name-confirm").textContent = "Create Folder";
  $("folder-name-input").value = "";
  $("folder-name-modal").classList.add("open");
  setTimeout(() => $("folder-name-input").focus(), 100);
}
const newFolderBtn = $("new-folder-btn");
if (newFolderBtn) newFolderBtn.addEventListener("click", () => openNewFolder());

$("folder-name-cancel").addEventListener("click", () => {
  $("folder-name-modal").classList.remove("open");
  S.renameFolderId = null; S.newFolderFor = null;
});
$("folder-name-input").addEventListener("keydown", e => {
  if (e.key === "Enter") $("folder-name-confirm").click();
});
$("folder-name-confirm").addEventListener("click", () => {
  const name = $("folder-name-input").value.trim();
  if (!name) return;
  if (S.renameFolderId) {
    const f = S.folders.find(x => x.id === S.renameFolderId);
    if (f) { f.name = name; saveFolders(); toast("Folder renamed"); }
    S.renameFolderId = null;
  } else {
    createFolder(name, S.newFolderFor);
    toast(S.newFolderFor ? `Moved into "${name}"` : `Folder "${name}" created`);
    S.newFolderFor = null;
    $("folder-pick-sheet").classList.remove("open");
  }
  $("folder-name-modal").classList.remove("open");
  renderFolders(); renderPlaylists();
});

/* ── "Move to folder" picker ─────────────────────────────────────────── */
function openFolderPicker(plid) {
  S.movePlId = plid;
  const list = $("folder-pick-list");
  const current = folderOf(plid);
  const rows = [];

  if (current) {
    rows.push(`<div class="pl-select-item" data-fid="">
      <div class="pl-select-thumb" style="background:var(--gray-200);color:var(--gray-600)"><i class="ti ti-folder-off"></i></div>
      <div><div class="pl-select-name">Remove from "${esc(current.name)}"</div>
      <div class="pl-select-count">Back to the top level</div></div></div>`);
  }
  for (const f of S.folders) {
    if (current && f.id === current.id) continue;
    const n = f.plIds.length;
    rows.push(`<div class="pl-select-item" data-fid="${esc(f.id)}">
      <div class="pl-select-thumb"><i class="ti ti-folder-filled"></i></div>
      <div><div class="pl-select-name">${esc(f.name)}</div>
      <div class="pl-select-count">${n} album${n!==1?"s":""}</div></div></div>`);
  }

  list.innerHTML = rows.length ? rows.join("")
    : `<div class="state-msg"><i class="ti ti-folder"></i><p>No folders yet.<br>Tap + to make one.</p></div>`;

  list.querySelectorAll(".pl-select-item").forEach(el => {
    el.addEventListener("click", () => {
      const fid = el.dataset.fid || null;
      movePlaylistToFolder(S.movePlId, fid);
      $("folder-pick-sheet").classList.remove("open");
      renderFolders(); renderPlaylists();
      toast(fid ? "Moved to folder" : "Moved out of folder");
    });
  });
  $("folder-pick-sheet").classList.add("open");
}
$("folder-pick-close").addEventListener("click", () => $("folder-pick-sheet").classList.remove("open"));
$("folder-pick-new").addEventListener("click", () => openNewFolder(S.movePlId));

/* ── Drag an album card onto a folder to file it ─────────────────────
   Touch-first: press and hold an album, a ghost follows your finger, and
   folders light up as you pass over them. Mouse users get the same via
   pointer events, so there's one code path to keep honest. */
function initAlbumDragToFolder() {
  const grid = $("playlist-grid");
  if (!grid || grid._dragBound) return;
  grid._dragBound = true;

  const HOLD = 420, MOVE_TOL = 14;
  let timer = null, card = null, ghost = null, dragging = false;
  let sx = 0, sy = 0, plid = null;

  const cleanup = () => {
    clearTimeout(timer); timer = null;
    if (ghost) { ghost.remove(); ghost = null; }
    if (card) card.classList.remove("dragging-card");
    document.querySelectorAll(".folder-card.drop-hot").forEach(f => f.classList.remove("drop-hot"));
    document.removeEventListener("pointermove", onDocMove);
    document.removeEventListener("pointerup", onDocUp);
    document.removeEventListener("pointercancel", cleanup);
    card = null; dragging = false; plid = null;
  };

  const makeGhost = (fromCard, x, y) => {
    const img = fromCard.querySelector(".pl-card-img img");
    ghost = document.createElement("div");
    ghost.className = "drag-ghost";
    ghost.innerHTML = img ? `<img src="${img.src}" alt="">` : `<div class="ph"></div>`;
    ghost.style.left = x + "px";
    ghost.style.top  = y + "px";
    document.body.appendChild(ghost);
  };

  // Tracked on document, not the grid: once the finger crosses out of the
  // album grid and over a folder card the grid stops seeing pointer events,
  // which is exactly when we most need them.
  function onDocMove(e) {
    if (!card) return;
    if (!dragging) {
      if (Math.abs(e.clientX - sx) > MOVE_TOL || Math.abs(e.clientY - sy) > MOVE_TOL) cleanup();
      return;
    }
    e.preventDefault?.();
    if (ghost) { ghost.style.left = e.clientX + "px"; ghost.style.top = e.clientY + "px"; }
    const over = document.elementFromPoint(e.clientX, e.clientY)?.closest(".folder-card");
    document.querySelectorAll(".folder-card").forEach(f =>
      f.classList.toggle("drop-hot", f === over));
  }

  function onDocUp(e) {
    if (!dragging) { cleanup(); return; }
    const over = document.elementFromPoint(e.clientX, e.clientY)?.closest(".folder-card");
    const targetId = over?.dataset.folder || null;
    const droppedPl = plid;
    cleanup();
    if (targetId && droppedPl) {
      const f = S.folders.find(x => x.id === targetId);
      movePlaylistToFolder(droppedPl, targetId);
      renderFolders(); renderPlaylists();
      toast(`Moved into "${f ? f.name : "folder"}"`);
    }
  }

  grid.addEventListener("pointerdown", e => {
    const c = e.target.closest(".pl-card[data-plid]");
    if (!c || e.target.closest(".pl-card-menu")) return;
    card = c; plid = c.dataset.plid;
    sx = e.clientX; sy = e.clientY;
    document.addEventListener("pointermove", onDocMove, { passive: false });
    document.addEventListener("pointerup", onDocUp, { passive: true });
    document.addEventListener("pointercancel", cleanup, { passive: true });
    timer = setTimeout(() => {
      // Only worth dragging if there's somewhere to drop
      if (!S.folders.length) { cleanup(); return; }
      dragging = true;
      card.classList.add("dragging-card");
      makeGhost(card, sx, sy);
      if (navigator.vibrate) { try { navigator.vibrate(14); } catch {} }
    }, HOLD);
  }, { passive: true });

  // Suppress the click that would otherwise open the album after a drag
  grid.addEventListener("click", e => {
    if (dragging) { e.stopImmediatePropagation(); e.preventDefault(); }
  }, true);
}

/* ── Library settings entry point (Appearance lives here too now) ────── */
const libSet = $("btn-lib-settings");
if (libSet) libSet.addEventListener("click", openHomeSettings);

/* ═══════════════════════════════════════════════════════════════════════
   v25: playback resilience + install awareness
═══════════════════════════════════════════════════════════════════════ */

/* ── Stream recovery ─────────────────────────────────────────────────
   On a phone-hosted app the network drops in and out. If the stream dies
   while the user still wants audio, re-attach the source and seek back to
   where we were, rather than silently stopping. We only do this when the
   user's intent is "playing" — an OS pause (phone call, audio focus lost)
   must be respected, not fought. */
S.wantPlaying = false;
let _recoverTries = 0;

audio.addEventListener("playing", () => { _recoverTries = 0; S.wantPlaying = true; });

function tryRecoverStream() {
  if (!S.wantPlaying || !S.track || S.loading) return;
  if (_recoverTries >= 2) return;              // don't loop forever
  if (!audio.src) return;
  _recoverTries++;
  const at = audio.currentTime || 0;
  const src = audio.src;
  window.ndlog && window.ndlog(`stream recovery attempt ${_recoverTries} @${at.toFixed(1)}s`);
  try {
    audio.load();
    audio.src = src;
    audio.currentTime = at;
    audio.play().catch(() => {});
  } catch {}
}

audio.addEventListener("error",   () => setTimeout(tryRecoverStream, 800));
audio.addEventListener("stalled", () => setTimeout(tryRecoverStream, 2500));

// Coming back to the foreground: if the user still wants audio but the
// element got stopped in the background, pick it back up where it left off.
document.addEventListener("visibilitychange", () => {
  if (document.hidden) return;
  if (S.wantPlaying && audio.paused && audio.src && !S.loading) {
    audio.play().catch(() => {});
  }
});

// Track user intent so recovery never fights a deliberate pause
const _origTogglePlay = typeof togglePlay === "function" ? togglePlay : null;
audio.addEventListener("play",  () => { S.wantPlaying = true;  });
$("ctrl-play")?.addEventListener("click", () => { S.wantPlaying = audio.paused; });
$("mini-play")?.addEventListener("click", () => { S.wantPlaying = audio.paused; });

/* ── Installed vs browser tab ────────────────────────────────────────
   Running inside a Chrome tab is the cause of two things people notice:
   the media notification shows Chrome's icon (a website can't override
   it — only an installed app can), and Android freezes background tabs
   far more aggressively than installed apps, so playback stops when you
   minimise. Installed to the home screen, both go away. We surface this
   once, in About, rather than nagging. */
function isInstalledPWA() {
  return window.matchMedia("(display-mode: standalone)").matches
      || window.matchMedia("(display-mode: fullscreen)").matches
      || window.matchMedia("(display-mode: minimal-ui)").matches
      || navigator.standalone === true;
}

function renderInstallState() {
  const box = $("install-state");
  if (!box) return;
  const rows = installDiagnostics();
  const installed = typeof isInstalledPWA === "function" && isInstalledPWA();

  const checks = rows.map(r =>
    `<li class="${r.warn ? "warn" : (r.ok ? "ok" : "no")}"><i class="ti ti-${r.warn ? "alert-triangle" : (r.ok ? "circle-check" : "circle-x")}"></i>${r.label}</li>`
  ).join("");

  if (installed) {
    box.className = "install-box ok";
    box.innerHTML = `<div class="install-h"><i class="ti ti-circle-check"></i> Installed as an app</div>
      <ul class="check-list">${checks}</ul>
      <p class="about-p">Background playback and the Naada icon in your media
      notification both work as intended.</p>`;
    return;
  }

  const httpsLocal = location.protocol === "https:" &&
    ["localhost", "127.0.0.1", "[::1]"].includes(location.hostname);

  box.className = "install-box warn";
  box.innerHTML = `<div class="install-h"><i class="ti ti-alert-triangle"></i> Running in a browser tab</div>
    <ul class="check-list">${checks}</ul>
    <p class="about-p">While Naada runs as a Chrome tab, two things stay broken
    no matter what the app does: music stops when you minimise, and the media
    notification shows Chrome's icon instead of ours.</p>
    <button class="modal-primary" id="install-now" style="margin-top:10px${_installPrompt ? "" : ";display:none"}">
      <i class="ti ti-download"></i> Install Naada
    </button>
    ${httpsLocal ? `
    <div class="fix-box">
      <div class="fix-h"><i class="ti ti-tool"></i> The fix — switch to plain HTTP</div>
      <p class="about-p">Chrome refuses to install any app served over HTTPS with
      a certificate you had to click past. But <b>http://localhost is trusted by
      Chrome automatically</b>, no certificate needed. In Termux:</p>
      <pre class="fix-code">./naada.sh stop
NAADA_HTTP_ONLY=1 ./naada.sh start</pre>
      <p class="about-p">Then open <b>http://localhost:5000</b> and the Install
      button above should appear.</p>
    </div>` : `
    <p class="about-p" style="margin-top:8px">If that button isn't showing,
    Chrome hasn't judged the app installable. Use <b>Chrome ⋮ → Add to Home
    Screen</b>; if the result still opens with a browser address bar, the
    certificate is the blocker (see INSTALL.txt).</p>`}`;

  const btn = $("install-now");
  if (btn) btn.addEventListener("click", doInstall);
}

/* ═══════════════════════════════════════════════════════════════════════
   v26: Android back button, visible row menus, install prompt, prefetch
═══════════════════════════════════════════════════════════════════════ */

/* ── The ⋮ on every song row ──────────────────────────────────────────
   Delete used to be reachable only by long-press, which is a gesture you
   have to already know about. A hidden gesture isn't a UI. The same menu
   is now one visible tap away on every row; long-press still works for
   anyone who's learned it. */
document.addEventListener("click", e => {
  const btn = e.target.closest(".t-menu[data-menu]");
  if (!btn) return;
  e.stopPropagation();
  e.preventDefault();
  const row = btn.closest(".track, .pl-row");
  const ctx = row ? tmContextOf(row) : "browse";
  const track = tmFindTrack(btn.dataset.menu, ctx);
  if (track) openTrackMenu(track, ctx);
}, true);

/* ── Android back button ─────────────────────────────────────────────
   The app had no History API integration at all, so the system back
   gesture went straight past it and minimised the whole app. Now every
   sheet, modal and tab change puts an entry on the history stack, and
   back unwinds them one at a time — closing the top sheet, then stepping
   back through tabs, and only exiting from Home with nothing open. */
(function initBackNav() {
  const OVERLAY_SEL = ".sheet, .modal-back, .now-screen";
  const stack = [];        // overlays currently open, in the order opened
  let syncingBack = false; // true while we're unwinding history ourselves

  try { history.replaceState({ nd: "root" }, ""); } catch {}

  function pushEntry(tag) {
    try { history.pushState({ nd: tag }, ""); } catch {}
  }

  // Watch every overlay for the .open class rather than wrapping ~20 call
  // sites — this also covers any overlay added later without extra wiring.
  const obs = new MutationObserver(muts => {
    for (const m of muts) {
      const el = m.target;
      const open = el.classList.contains("open");
      const at = stack.indexOf(el);

      if (open && at === -1) {
        stack.push(el);
        pushEntry(el.id || "overlay");
      } else if (!open && at !== -1) {
        stack.splice(at, 1);
        // Closed by a button rather than by back — drop the matching
        // history entry so the stack doesn't drift out of step.
        if (!syncingBack) {
          syncingBack = true;
          try { history.back(); } catch { syncingBack = false; }
        }
      }
    }
  });
  document.querySelectorAll(OVERLAY_SEL).forEach(el =>
    obs.observe(el, { attributes: true, attributeFilter: ["class"] }));

  // Tab changes are history too: back from Library lands on Home
  const pageStack = [];
  window.ndNavPage = function (from, to) {
    if (from && from !== to) {
      pageStack.push(from);
      pushEntry("page:" + to);
    }
  };

  window.addEventListener("popstate", () => {
    // This popstate is the one we triggered ourselves after a UI close
    if (syncingBack) { syncingBack = false; return; }

    // 1. Close the topmost open overlay
    const top = stack[stack.length - 1];
    if (top) {
      syncingBack = true;             // stop the observer re-firing history.back
      top.classList.remove("open");
      stack.pop();
      syncingBack = false;
      return;
    }

    // 2. Otherwise step back through tabs
    if (pageStack.length) {
      const prev = pageStack.pop();
      if (typeof gotoSilent === "function") gotoSilent(prev);
      return;
    }

    // 3. Nothing left to unwind — let the system have the back press,
    //    which minimises the app the way any Android app would.
  });
})();

/* goto() that doesn't add to history — used when replaying the back stack */
function gotoSilent(page) {
  if (typeof goto !== "function") return;
  window._ndSilentNav = true;
  try { goto(page); } finally { window._ndSilentNav = false; }
}

/* ── Prefetch the next track's stream URL ────────────────────────────
   The audio itself streams from the CDN, but the URL for each song comes
   from the Termux server. If the phone backgrounds the app and Android
   throttles Termux, that lookup can fail exactly at a track change — the
   music stops between songs rather than mid-song. Fetching the next URL
   while we're still in the foreground removes that dependency. */
S.nextUrl = null;

async function prefetchNextTrack() {
  try {
    if (!S.queue?.length) return;
    const nextIdx = S.shuffle ? -1 : S.queueIdx + 1;
    const nxt = nextIdx >= 0 ? S.queue[nextIdx] : null;
    if (!nxt || S.nextUrl?.id === nxt.id) return;
    const r = await fetch(`/api/song/${nxt.id}`);
    if (!r.ok) return;
    const d = await r.json();
    if (d.url) {
      S.nextUrl = { id: nxt.id, url: d.url };
      window.ndlog && window.ndlog(`prefetched next: ${nxt.title}`);
    }
  } catch { /* best effort only */ }
}

// Once a song is properly under way, quietly line up the one after it
audio.addEventListener("playing", () => setTimeout(prefetchNextTrack, 4000));

/* ── Install to home screen ──────────────────────────────────────────
   Chrome fires beforeinstallprompt only when the app is actually
   installable (valid HTTPS, manifest, service worker). Capturing it lets
   us offer a real Install button instead of telling people to hunt
   through a browser menu — and its absence is itself a useful signal,
   which the diagnostics below report honestly. */
let _installPrompt = null;

window.addEventListener("beforeinstallprompt", e => {
  e.preventDefault();
  _installPrompt = e;
  if (typeof renderInstallState === "function") renderInstallState();
  const btn = $("install-now");
  if (btn) btn.style.display = "";
});

window.addEventListener("appinstalled", () => {
  _installPrompt = null;
  toast("Naada installed — open it from your home screen");
  if (typeof renderInstallState === "function") renderInstallState();
});

async function doInstall() {
  if (!_installPrompt) {
    toast("Use Chrome ⋮ → Add to Home Screen");
    return;
  }
  _installPrompt.prompt();
  try { await _installPrompt.userChoice; } catch {}
  _installPrompt = null;
}

/* Honest diagnostics — each line is measured, not assumed */
function installDiagnostics() {
  const rows = [];
  const secure = window.isSecureContext;
  const sw = !!navigator.serviceWorker?.controller;
  const installed = typeof isInstalledPWA === "function" && isInstalledPWA();
  const isHttps = location.protocol === "https:";
  const localHost = ["localhost", "127.0.0.1", "[::1]"].includes(location.hostname);

  rows.push({ ok: installed, label: installed
      ? "Running as an installed app"
      : "Running in a browser tab" });
  rows.push({ ok: secure, label: secure
      ? `Secure context (${location.protocol}//${location.hostname})`
      : "Not a secure context — install is blocked" });
  rows.push({ ok: sw, label: sw
      ? "Service worker active"
      : "Service worker not controlling this page" });
  rows.push({ ok: !!_installPrompt || installed, label: (_installPrompt || installed)
      ? "Chrome reports the app as installable"
      : "Chrome has not offered an install prompt" });

  // The specific trap: self-signed HTTPS on localhost. Chrome treats a
  // clicked-through certificate warning as a certificate ERROR, which blocks
  // installation outright — while plain http://localhost is on Chrome's
  // trustworthy-origins list and needs no certificate at all.
  if (isHttps && localHost && !installed && !_installPrompt) {
    rows.push({ ok: false, warn: true, label:
      "Self-signed HTTPS on localhost — this is almost certainly the blocker. "
      + "Plain http://localhost is MORE installable than https:// with a "
      + "certificate you had to click through." });
  }
  return rows;
}

/* ═══════════════════════════════════════════════════════════════════════
   v28: productivity — resume, recent searches, play counts, sleep timer
═══════════════════════════════════════════════════════════════════════ */

/* ── Resume where you left off ───────────────────────────────────────
   A daily-driver player that forgets what you were listening to makes you
   do navigation work every single launch. We remember the track, the
   queue and the position, then restore it paused — never auto-playing,
   because audio starting by itself when you open an app is hostile. */
const RESUME_KEY = "nd_resume";

function saveResume() {
  if (!S.track) return;
  const at = Math.floor(audio.currentTime || 0);
  // A zero position must never overwrite a real one for the same track.
  // Real scenario this fixes: you open the app, resume restores you at
  // 1:37 but paused, you don't press play, you close the app — pagehide
  // then wrote at:0 and your place was silently lost.
  if (at < 1) {
    const prev = LS.get(RESUME_KEY, null);
    if (prev?.track?.id === S.track.id && prev.at > 1) return;
  }
  try {
    LS.set(RESUME_KEY, {
      track: S.track,
      queue: (S.queue || []).slice(0, 60),
      idx: S.queueIdx,
      at,
      saved: Date.now(),
    });
  } catch {}
}

// Save on the events that matter, cheaply — not on every timeupdate
let _resumeTick = 0;
audio.addEventListener("timeupdate", () => {
  const now = Date.now();
  if (now - _resumeTick < 5000) return;   // at most once every 5s
  _resumeTick = now;
  saveResume();
});
audio.addEventListener("pause", saveResume);
window.addEventListener("pagehide", saveResume);
document.addEventListener("visibilitychange", () => { if (document.hidden) saveResume(); });

function restoreResume() {
  let r;
  try { r = LS.get(RESUME_KEY, null); } catch { return; }
  if (!r?.track) return;
  // Anything older than a week is stale — you've moved on
  if (r.saved && Date.now() - r.saved > 7 * 24 * 3600 * 1000) return;

  S.track    = r.track;
  S.queue    = Array.isArray(r.queue) && r.queue.length ? r.queue : [r.track];
  S.queueIdx = typeof r.idx === "number" ? r.idx : 0;
  S.resumeAt = r.at || 0;

  // Show the mini-player in a paused, ready state. Nothing loads or plays
  // until the user asks — no surprise audio, no wasted mobile data.
  updateMeta(r.track);
  const mini = $("mini-player");
  if (mini) mini.classList.add("active");
  setPlayIcons(false);
  S.playing = false;

  const st = $("resume-strip");
  if (st && r.at > 15) {
    st.style.display = "flex";
    $("resume-label").textContent = `Resume "${r.track.title}" at ${fmt(r.at)}`;
    st.onclick = () => {
      st.style.display = "none";
      play(S.track, S.queue, S.queueIdx);
    };
  }
}

// After play() attaches a source, jump to the remembered position once
audio.addEventListener("loadedmetadata", () => {
  if (S.resumeAt && S.track) {
    const at = S.resumeAt;
    S.resumeAt = 0;
    if (at > 5 && at < (audio.duration || 0) - 5) {
      try { audio.currentTime = at; } catch {}
    }
  }
});

/* ── Play counts → a "Most played" shelf that reflects you ───────────
   Generic recommendations are guesses. Your own play history isn't. */
S.playCounts = LS.get("nd_playcounts", {});

function bumpPlayCount(t) {
  if (!t?.id) return;
  const e = S.playCounts[t.id] || { n: 0, t };
  e.n += 1; e.t = { id:t.id, title:t.title, artist:t.artist, image:t.image, duration:t.duration };
  e.last = Date.now();
  S.playCounts[t.id] = e;
  LS.set("nd_playcounts", S.playCounts);
}

function mostPlayed(limit = 12) {
  return Object.values(S.playCounts)
    .filter(e => e.n >= 2)              // one listen isn't a favourite
    .sort((a,b) => b.n - a.n || b.last - a.last)
    .slice(0, limit)
    .map(e => e.t);
}

/* ── Recent searches ─────────────────────────────────────────────────
   Re-typing the same artist every session is pure friction. */
S.recentSearches = LS.get("nd_recent_searches", []);

function addRecentSearch(q) {
  q = (q || "").trim();
  if (q.length < 2) return;
  S.recentSearches = [q, ...S.recentSearches.filter(x => x.toLowerCase() !== q.toLowerCase())].slice(0, 8);
  LS.set("nd_recent_searches", S.recentSearches);
}

function renderRecentSearches() {
  const box = $("recent-searches");
  if (!box) return;
  if (!S.recentSearches.length || S.searchQ) { box.style.display = "none"; return; }
  box.style.display = "block";
  box.innerHTML = `<div class="recent-h">
      <span>Recent</span>
      <button class="recent-clear" id="recent-clear">Clear</button>
    </div>
    <div class="recent-chips">${
      S.recentSearches.map(q =>
        `<button class="recent-chip" data-q="${esc(q)}"><i class="ti ti-history"></i>${esc(q)}</button>`
      ).join("")}</div>`;
  box.querySelectorAll(".recent-chip").forEach(el =>
    el.addEventListener("click", () => {
      const inp = $("search-input");
      inp.value = el.dataset.q;
      S.searchQ = el.dataset.q;
      $("search-clear")?.classList.add("vis");
      doSearch(el.dataset.q);
      renderRecentSearches();
    }));
  const clr = $("recent-clear");
  if (clr) clr.addEventListener("click", () => {
    S.recentSearches = []; LS.set("nd_recent_searches", []);
    renderRecentSearches();
  });
}

/* ── Sleep timer ─────────────────────────────────────────────────────
   The logic already existed but had no way in. For a player used at
   night this is a headline feature, not a setting. */
function startSleepTimer(mins) {
  clearTimeout(S.sleepTimer);
  if (!mins) {
    S.sleepEnd = null; S.sleepTimer = null;
    updateSleepChip(); toast("Sleep timer off");
    return;
  }
  S.sleepEnd = Date.now() + mins * 60000;
  S.sleepTimer = setTimeout(() => {
    // Fade out rather than cutting — waking someone with a hard stop is rude
    const startVol = audio.volume;
    let v = startVol;
    const fade = setInterval(() => {
      v -= startVol / 20;
      if (v <= 0) {
        clearInterval(fade);
        audio.pause(); audio.volume = startVol;
        S.playing = false; setPlayIcons(false); vinylPlay(false);
        S.sleepEnd = null; updateSleepChip();
      } else { audio.volume = Math.max(0, v); }
    }, 250);
  }, mins * 60000);
  updateSleepChip();
  toast(`Sleeping in ${mins} min`);
}

function updateSleepChip() {
  const chip = $("sleep-chip");
  if (!chip) return;
  if (!S.sleepEnd) { chip.style.display = "none"; return; }
  chip.style.display = "inline-flex";
  const tick = () => {
    if (!S.sleepEnd) { chip.style.display = "none"; return; }
    const left = Math.max(0, Math.round((S.sleepEnd - Date.now()) / 60000));
    chip.innerHTML = `<i class="ti ti-moon"></i> ${left}m`;
  };
  tick();
  clearInterval(S._sleepInt);
  S._sleepInt = setInterval(tick, 30000);
}

/* ═══════════════════════════════════════════════════════════════════════
   v28: Radio — 45,000 free stations via Radio Browser (open source, no key)
   Deliberately a search filter, not a fifth nav tab. Radio is a way of
   listening, not a separate place to go, and the nav bar has earned its
   current calm.
═══════════════════════════════════════════════════════════════════════ */

async function searchRadio(q) {
  const res = $("search-results");
  res.innerHTML = `<div class="load-row"><span class="spinner"></span> Finding stations…</div>`;
  try {
    const url = `/api/radio/search?q=${encodeURIComponent(q || "")}&lang=${encodeURIComponent(S.homeLang || "hindi")}`;
    const r = await fetch(url);
    const d = await r.json();
    const st = d.stations || [];
    if (!st.length) {
      res.innerHTML = `<div class="state-msg"><i class="ti ti-radio"></i><p>${
        d.error === "radio_unavailable"
          ? "Radio directory unreachable.<br>Check your connection and try again."
          : "No stations found.<br>Try an artist, genre or city."
      }</p></div>`;
      return;
    }
    res.innerHTML = st.map(s =>
      `<div class="track radio-row" data-id="${esc(s.id)}">
         <div class="t-img">${s.image
            ? `<img src="${esc(s.image)}" alt="" loading="lazy" onerror="this.replaceWith(Object.assign(document.createElement('span'),{className:'t-img-ph',textContent:'📻'}))">`
            : '<span class="t-img-ph">📻</span>'}</div>
         <div class="t-info">
           <div class="t-title">${esc(s.title)}</div>
           <div class="t-meta">${esc(s.artist)}${s.tags ? " · " + esc(s.tags.split(",")[0]) : ""}</div>
         </div>
         <div class="t-right"><span class="live-dot" title="Live"></span></div>
       </div>`).join("");

    _listRegistry.set(res, st);
    res.querySelectorAll(".radio-row").forEach(row =>
      row.addEventListener("click", () => {
        const s = st.find(x => x.id === row.dataset.id);
        if (s) playRadio(s);
      }));
  } catch {
    res.innerHTML = `<div class="state-msg"><i class="ti ti-radio"></i><p>Couldn't reach the radio directory.</p></div>`;
  }
}

/* Radio streams straight from the station — no /api/song lookup, no
   duration, and next/prev make no sense on a live feed. */
function playRadio(s) {
  S.track = s;
  S.queue = [s]; S.queueIdx = 0;
  S.wantPlaying = true;
  S.nextUrl = null;
  audio.src = s.url;
  audio.play().catch(() => toast("Station wouldn't start — try another"));
  updateMeta(s);
  const mini = $("mini-player"); if (mini) mini.classList.add("active");
  S.playing = true; setPlayIcons(true); vinylPlay(true);
  applyRadioMode(true);   // set now, not on 'playing' — a dead stream would
                          // otherwise leave a seek bar that does nothing
  toast(`📻 ${s.title}`);
  // Let the directory know, so good stations rise for everyone
  const uuid = s.id.replace(/^radio:/, "");
  fetch(`/api/radio/click/${encodeURIComponent(uuid)}`, { method: "POST" }).catch(() => {});
}

/* Radio has no track list, so hide the controls that would lie */
function applyRadioMode(on) {
  document.body.classList.toggle("radio-mode", !!on);
}

/* ── Hook Radio into the existing search chips ───────────────────────── */
(function initRadioSearch() {
  const origDo = window.doSearch;
  window.doSearch = function (q) {
    if (S.sType === "radio") { addRecentSearch(q); return searchRadio(q); }
    if (q) addRecentSearch(q);
    return origDo ? origDo.apply(this, arguments) : undefined;
  };

  document.querySelectorAll(".s-chip").forEach(chip => {
    chip.addEventListener("click", () => {
      if (chip.dataset.stype !== "radio") return;
      const res = $("search-results");
      // Empty query on the Radio chip = popular stations in your language,
      // which is a far better cold start than a blank panel.
      searchRadio(S.searchQ || "");
      if (res) res.scrollTop = 0;
    });
  });
})();

/* ── Sleep timer UI ──────────────────────────────────────────────────── */
(function initSleepUI() {
  const btn = $("sleep-btn");
  if (!btn) return;
  btn.addEventListener("click", () => {
    const sheet = $("sleep-sheet");
    if (sheet) sheet.classList.add("open");
  });
  document.querySelectorAll("[data-sleep]").forEach(el =>
    el.addEventListener("click", () => {
      startSleepTimer(parseInt(el.dataset.sleep) || 0);
      $("sleep-sheet")?.classList.remove("open");
    }));
  $("sleep-close")?.addEventListener("click", () => $("sleep-sheet")?.classList.remove("open"));
})();

/* ── Boot the productivity bits ──────────────────────────────────────── */
(function bootV28() {
  try { restoreResume(); } catch {}
  try { renderRecentSearches(); } catch {}
  // Count a play once it's actually under way, not on every skip
  audio.addEventListener("playing", () => {
    if (S.track && !S.track.is_radio) bumpPlayCount(S.track);
  });
  audio.addEventListener("playing", () => applyRadioMode(!!S.track?.is_radio));
})();

/* ═══════════════════════════════════════════════════════════════════════
   v29: Podcasts
   A podcast is not a long song. Different data shape (show -> episode),
   different playback (speed, skip 30s, resume ALWAYS), and shuffle/repeat
   are meaningless. So it gets its own model rather than being forced
   through the music one — but it reuses the same player, queue and
   mini-player so there's nothing new to learn.
═══════════════════════════════════════════════════════════════════════ */

S.podSubs  = LS.get("nd_pod_subs", []);      // followed shows
S.epProg   = LS.get("nd_ep_progress", {});   // per-episode position + played
S.podShow  = null;                            // show open in the sheet
S.speed    = LS.get("nd_speed", 1);

function savePodSubs() { LS.set("nd_pod_subs", S.podSubs); }
function saveEpProg()  { LS.set("nd_ep_progress", S.epProg); }

/* ── Discovery ───────────────────────────────────────────────────────── */
async function searchPodcasts(q) {
  const box = $("pod-results");
  if (!q) { box.innerHTML = ""; renderPodSubs(); return; }
  box.innerHTML = `<div class="load-row"><span class="spinner"></span> Searching shows…</div>`;
  try {
    const r = await fetch(`/api/podcast/search?q=${encodeURIComponent(q)}`);
    const d = await r.json();
    const shows = d.shows || [];
    if (!shows.length) {
      box.innerHTML = `<div class="state-msg"><i class="ti ti-microphone-2"></i><p>${
        d.error ? "Podcast directory unreachable." : "No shows found."}</p></div>`;
      return;
    }
    box.innerHTML = `<div class="sec-hdr"><span class="sec-title">Shows</span></div>` +
      shows.map(sh =>
      `<div class="pod-row" data-pid="${esc(sh.id)}">
         <div class="pod-art">${sh.image ? `<img src="${esc(sh.image)}" alt="" loading="lazy">` : "🎙️"}</div>
         <div class="pod-meta">
           <div class="pod-name">${esc(sh.title)}</div>
           <div class="pod-by">${esc(sh.author)}</div>
           <div class="pod-tag">${esc(sh.genre)}${sh.episodes ? ` · ${sh.episodes} episodes` : ""}</div>
         </div>
       </div>`).join("");
    _listRegistry.set(box, shows);
    box.querySelectorAll(".pod-row").forEach(row =>
      row.addEventListener("click", () => {
        const sh = shows.find(x => x.id === row.dataset.pid);
        if (sh) openShow(sh);
      }));
  } catch {
    box.innerHTML = `<div class="state-msg"><i class="ti ti-microphone-2"></i><p>Search failed.</p></div>`;
  }
}

/* ── Show detail + episodes ──────────────────────────────────────────── */
async function openShow(sh) {
  S.podShow = sh;
  $("pod-show-title").textContent = sh.title;
  $("pod-show-head").innerHTML =
    `<div class="pod-head-art">${sh.image ? `<img src="${esc(sh.image)}" alt="">` : "🎙️"}</div>
     <div class="pod-head-meta">
       <div class="pod-head-title">${esc(sh.title)}</div>
       <div class="pod-head-by">${esc(sh.author || "")}</div>
     </div>`;
  updateFollowBtn();
  $("pod-show-sheet").classList.add("open");

  const list = $("pod-ep-list");
  list.innerHTML = `<div class="load-row"><span class="spinner"></span> Loading episodes…</div>`;
  try {
    const r = await fetch(`/api/podcast/episodes?feed=${encodeURIComponent(sh.feed)}`);
    const d = await r.json();
    const eps = d.episodes || [];
    if (!eps.length) {
      list.innerHTML = `<div class="state-msg"><i class="ti ti-rss"></i><p>Couldn't read this feed.</p></div>`;
      return;
    }
    S.podEpisodes = eps;
    renderEpisodes(eps);
  } catch {
    list.innerHTML = `<div class="state-msg"><i class="ti ti-rss"></i><p>Couldn't reach the feed.</p></div>`;
  }
}

function renderEpisodes(eps) {
  const list = $("pod-ep-list");
  list.innerHTML = eps.map(e => {
    const p = S.epProg[e.id];
    const pct = p && e.duration ? Math.min(100, Math.round((p.at / e.duration) * 100)) : 0;
    const done = p?.played;
    return `<div class="ep-row${done ? " done" : ""}" data-eid="${esc(e.id)}">
      <div class="ep-main">
        <div class="ep-title">${done ? '<i class="ti ti-circle-check"></i> ' : ""}${esc(e.title)}</div>
        <div class="ep-sub">${esc(e.date || "")}${e.duration ? ` · ${fmt(e.duration)}` : ""}${
          p && !done && p.at > 30 ? ` · ${fmt(p.at)} in` : ""}</div>
        ${pct > 2 && !done ? `<div class="ep-bar"><span style="width:${pct}%"></span></div>` : ""}
      </div>
      <button class="ep-play" aria-label="Play"><i class="ti ti-player-play-filled"></i></button>
    </div>`;
  }).join("");
  list.querySelectorAll(".ep-row").forEach(row =>
    row.addEventListener("click", () => {
      const e = eps.find(x => x.id === row.dataset.eid);
      if (e) playEpisode(e, eps);
    }));
}

/* ── Playback ────────────────────────────────────────────────────────
   Episodes always resume. Nobody restarts a 90-minute interview from
   zero because they closed the app. */
function playEpisode(ep, list) {
  S.track = ep;
  S.queue = list || [ep];
  S.queueIdx = S.queue.findIndex(x => x.id === ep.id);
  S.wantPlaying = true;
  S.nextUrl = null;

  const p = S.epProg[ep.id];
  S.resumeAt = p && !p.played && p.at > 20 ? p.at : 0;

  audio.src = ep.url;
  audio.playbackRate = S.speed;
  audio.play().catch(() => toast("Episode wouldn't start"));
  updateMeta(ep);
  const mini = $("mini-player"); if (mini) mini.classList.add("active");
  S.playing = true; setPlayIcons(true); vinylPlay(true);
  applyPodcastMode(true);
  if (S.resumeAt) toast(`Resuming at ${fmt(S.resumeAt)}`);
}

function applyPodcastMode(on) {
  document.body.classList.toggle("podcast-mode", !!on);
}

/* Track per-episode progress, and mark finished near the end */
let _epTick = 0;
audio.addEventListener("timeupdate", () => {
  const t = S.track;
  if (!t?.is_podcast) return;
  const now = Date.now();
  if (now - _epTick < 5000) return;
  _epTick = now;
  const at = Math.floor(audio.currentTime || 0);
  const dur = audio.duration || t.duration || 0;
  const played = dur > 0 && at > dur - 45;      // credits count as finished
  S.epProg[t.id] = { at: played ? 0 : at, played, when: Date.now() };
  saveEpProg();
});

/* ── Follow / unfollow ───────────────────────────────────────────────── */
function isFollowing(id) { return S.podSubs.some(s => s.id === id); }

function updateFollowBtn() {
  const btn = $("pod-follow");
  if (!btn || !S.podShow) return;
  const on = isFollowing(S.podShow.id);
  btn.innerHTML = `<i class="ti ti-${on ? "check" : "plus"}"></i>`;
  btn.classList.toggle("following", on);
  btn.setAttribute("aria-label", on ? "Unfollow" : "Follow");
}

$("pod-follow")?.addEventListener("click", () => {
  if (!S.podShow) return;
  if (isFollowing(S.podShow.id)) {
    S.podSubs = S.podSubs.filter(s => s.id !== S.podShow.id);
    toast("Unfollowed");
  } else {
    S.podSubs = [{ ...S.podShow }, ...S.podSubs].slice(0, 60);
    toast(`Following "${S.podShow.title}"`);
  }
  savePodSubs(); updateFollowBtn(); renderPodSubs();
});

function renderPodSubs() {
  const row = $("pod-subs"), hdr = $("pod-subs-hdr");
  if (!row) return;
  if (!S.podSubs.length) { row.innerHTML = ""; if (hdr) hdr.style.display = "none"; return; }
  if (hdr) hdr.style.display = "flex";
  row.innerHTML = S.podSubs.map(sh =>
    `<div class="mini-card" data-pid="${esc(sh.id)}">
       <div class="mini-card-img">${sh.image ? `<img src="${esc(sh.image)}" alt="">` : "🎙️"}</div>
       <div class="mini-card-title">${esc(sh.title)}</div>
       <div class="mini-card-sub">${esc(sh.author || "")}</div>
     </div>`).join("");
  row.querySelectorAll(".mini-card[data-pid]").forEach(el =>
    el.addEventListener("click", () => {
      const sh = S.podSubs.find(x => x.id === el.dataset.pid);
      if (sh) openShow(sh);
    }));
}

/* ── Speed + skip, the two controls podcasts actually need ───────────── */
const SPEEDS = [1, 1.25, 1.5, 1.75, 2];

$("speed-btn")?.addEventListener("click", () => {
  const i = SPEEDS.indexOf(S.speed);
  S.speed = SPEEDS[(i + 1) % SPEEDS.length];
  audio.playbackRate = S.speed;
  LS.set("nd_speed", S.speed);
  $("speed-btn").textContent = `${S.speed}×`;
  toast(`Speed ${S.speed}×`);
});

function skipBy(secs) {
  if (!audio.duration) return;
  audio.currentTime = Math.max(0, Math.min(audio.duration, audio.currentTime + secs));
}

/* In podcast mode the prev/next buttons become skip back / skip forward,
   because jumping between 90-minute episodes is almost never what you
   want mid-listen, and missing 30 seconds is. */
$("ctrl-prev")?.addEventListener("click", e => {
  if (!S.track?.is_podcast) return;
  e.stopImmediatePropagation(); skipBy(-30);
}, true);
$("ctrl-next")?.addEventListener("click", e => {
  if (!S.track?.is_podcast) return;
  e.stopImmediatePropagation(); skipBy(30);
}, true);

/* ── Page wiring ─────────────────────────────────────────────────────── */
(function initPodcasts() {
  const inp = $("pod-input"), clr = $("pod-clear");
  let t;
  inp?.addEventListener("input", e => {
    const q = e.target.value.trim();
    clr?.classList.toggle("vis", !!q);
    clearTimeout(t);
    t = setTimeout(() => searchPodcasts(q), 450);
  });
  clr?.addEventListener("click", () => {
    inp.value = ""; clr.classList.remove("vis");
    searchPodcasts(""); inp.focus();
  });
  $("pod-show-close")?.addEventListener("click", () =>
    $("pod-show-sheet").classList.remove("open"));

  // Trivia moved out of the nav and into Library
  $("open-trivia")?.addEventListener("click", () => goto("trivia"));
  $("trivia-back")?.addEventListener("click", () => goto("library"));

  renderPodSubs();
  if (S.speed !== 1) {
    audio.playbackRate = S.speed;
    const sb = $("speed-btn"); if (sb) sb.textContent = `${S.speed}×`;
  }
  // Music never inherits a podcast speed setting
  audio.addEventListener("playing", () => {
    const pod = !!S.track?.is_podcast;
    applyPodcastMode(pod);
    audio.playbackRate = pod ? S.speed : 1;
  });
})();
