/* ═══════════════════════════════════════════════════════════════════════
   Naada (నాద) — app.js  ·  Production music player
═══════════════════════════════════════════════════════════════════════ */
const APP_VERSION = "v22";

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
  playlists:  LS.get("nd_pls",     []),     // [{id,name,songs:[]}]
  history:    LS.get("nd_history", []),
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
};

/* ── DOM ────────────────────────────────────────────────────────────────── */
const $  = id  => document.getElementById(id);
const $$ = sel => document.querySelectorAll(sel);

/* ── Utility ────────────────────────────────────────────────────────────── */
function fmt(s) {
  if (!s || isNaN(s)) return "--:--";
  return `${Math.floor(s/60)}:${String(Math.floor(s%60)).padStart(2,"0")}`;
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
  S.page = page;
  $$(".page").forEach(el => el.classList.remove("active"));
  $$(".nav-item").forEach(el => el.classList.toggle("active", el.dataset.page === page));
  $(`page-${page}`).classList.add("active");
  if (page === "library") renderLibrary();
  if (page === "queue")   renderQueueEditable();
  if (page === "trivia")  loadTrivia();
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
    window.ndlog && window.ndlog(`  fetching /api/song/${track.id}`);
    const r = await fetch(`/api/song/${track.id}`);
    const d = await r.json();
    window.ndlog && window.ndlog(`  got response: url=${(d.url||"").slice(0,40)}`);

    // Validate stream URL — must be absolute https:// to avoid 404 on local server
    const streamUrl = d.url || "";
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
  if (!S.playlists.length) {
    grid.innerHTML = `<div style="grid-column:1/-1"><div class="state-msg" style="padding:20px"><i class="ti ti-playlist"></i><p>No playlists yet</p></div></div>`;
    return;
  }
  grid.innerHTML = S.playlists.map(pl => {
    const first = pl.songs?.[0];
    return `<div class="pl-card" data-plid="${esc(pl.id)}">
      <div class="pl-card-img">
        ${first?.image ? `<img src="${esc(first.image)}" alt="">` : "🎵"}
      </div>
      <div class="pl-card-name">${esc(pl.name)}</div>
      <div class="pl-card-count">${pl.songs.length} song${pl.songs.length!==1?"s":""}</div>
    </div>`;
  }).join("");
  grid.querySelectorAll(".pl-card[data-plid]").forEach(el =>
    el.addEventListener("click", () => openPlaylistDetail(el.dataset.plid))
  );
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
  const et = $("pl-edit-toggle"); if (et) et.textContent = "Edit";
  $("pl-detail-title").textContent = pl.name;
  renderPlaylistDetail(pl);
  $("pl-detail-sheet").classList.add("open");
}

$("pl-detail-close").addEventListener("click", () => $("pl-detail-sheet").classList.remove("open"));

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

function openAddToPlaylist() {
  const list = $("pl-select-list");
  if (!S.playlists.length) {
    list.innerHTML = `<div class="state-msg"><i class="ti ti-playlist"></i><p>No playlists yet.<br>Create one in Library.</p></div>`;
  } else {
    list.innerHTML = S.playlists.map(pl => {
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
        if (!pl || !S.addPlTrack) return;
        if (pl.songs.some(s => s.id === S.addPlTrack.id)) { toast("Already in this playlist"); return; }
        pl.songs.push({...S.addPlTrack});
        // Surface the just-updated playlist: move it to the front + float shelf
        S.playlists = [pl, ...S.playlists.filter(p => p.id !== pl.id)];
        save(); $("add-pl-sheet").classList.remove("open");
        touchShelf("playlists");
        renderHomeLibraryShelves();
        toast(`Added to "${pl.name}"`);
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
    if (el) el.style.display = S.homeCfg.shelves.includes(def.id) ? "" : "none";
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
  list.innerHTML = pl.songs.map((t,i) =>
    `<div class="track pl-row${S.track?.id===t.id?" now":""}" data-idx="${i}" data-id="${esc(t.id)}">
       <span class="t-num">${i+1}</span>
       <div class="t-img">${t.image?`<img src="${esc(t.image)}" alt="" loading="lazy">`:'<span class="t-img-ph">🎵</span>'}</div>
       <div class="t-info"><div class="t-title">${esc(t.title)}</div><div class="t-meta">${esc(t.artist||"")}</div></div>
       ${S.plEditMode
         ? `<button class="q-remove" data-rm="${i}" aria-label="Remove"><i class="ti ti-x"></i></button>`
         : `<span class="t-dur">${fmt(t.duration)}</span>`}
     </div>`).join("");

  list.querySelectorAll(".pl-row").forEach(row => {
    row.addEventListener("click", e => {
      if (e.target.closest(".q-remove")) return;
      const i = parseInt(row.dataset.idx);
      if (!isNaN(i) && pl.songs[i]) play(pl.songs[i], pl.songs, i);
    });
  });
  list.querySelectorAll(".q-remove").forEach(btn => {
    btn.addEventListener("click", e => {
      e.stopPropagation();
      const i = parseInt(btn.dataset.rm);
      pl.songs.splice(i,1);
      save();
      renderPlaylistDetail(pl);
      renderHomeLibraryShelves();
    });
  });
}

const plEditToggle = $("pl-edit-toggle");
if (plEditToggle) plEditToggle.addEventListener("click", () => {
  S.plEditMode = !S.plEditMode;
  plEditToggle.textContent = S.plEditMode ? "Done" : "Edit";
  const pl = S.playlists.find(p => p.id === S.viewPlId);
  if (pl) renderPlaylistDetail(pl);
});

const plMenu = $("pl-detail-menu");
if (plMenu) plMenu.addEventListener("click", () => {
  const pl = S.playlists.find(p => p.id === S.viewPlId);
  if (!pl) return;
  const choice = prompt(`Playlist "${pl.name}"\n\nType: rename  |  delete  |  cancel`, "rename");
  if (choice === "rename") {
    const name = prompt("New playlist name:", pl.name);
    if (name && name.trim()) { pl.name = name.trim(); save(); $("pl-detail-title").textContent = pl.name; renderLibrary(); toast("Renamed"); }
  } else if (choice === "delete") {
    S.playlists = S.playlists.filter(p => p.id !== pl.id);
    save(); $("pl-detail-sheet").classList.remove("open"); renderLibrary(); renderHomeLibraryShelves();
    toast(`"${pl.name}" deleted`);
  }
});

// New-playlist button inside add-to-playlist sheet
const addPlNew = $("add-pl-new");
if (addPlNew) addPlNew.addEventListener("click", () => {
  $("add-pl-sheet").classList.remove("open");
  $("create-pl-modal").classList.add("open");
  setTimeout(() => $("pl-name-input")?.focus(), 100);
});

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
