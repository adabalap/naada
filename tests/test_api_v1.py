"""
API v1 tests. JioSaavn is mocked, so these run offline:

    NAADA_TOKEN=t python -m pytest -q tests/        (or)  python tests/test_api_v1.py
"""
import os
import sys
import tempfile
import unittest

os.environ["NAADA_TOKEN"] = "test-token"
os.environ["NAADA_DB"] = os.path.join(tempfile.mkdtemp(), "library.db")
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import app as naada  # noqa: E402

SONG = {"id": "abc123", "title": "Samajavaragamana (From &quot;AVPL&quot;) &amp; more", "type": "song",
        "more_info": {"album": "Ala Vaikunthapurramuloo", "duration": "228",
                      "media_url": "https://aac.saavncdn.com/x/abc_96.mp4",
                      "artistMap": {"primary_artists": [{"name": "Sid Sriram"}]}},
        "image": "https://c.saavncdn.com/a-150x150.jpg"}


def fake_saavn(params, timeout=12):
    call = params.get("__call")
    if call == "song.getDetails":
        return {SONG["id"]: SONG} if params["pids"] == SONG["id"] else {}
    if call == "search.getResults":
        return {"results": [SONG]}
    return {}


naada.saavn = fake_saavn
H = {"X-Naada-Token": "test-token"}


class V1(unittest.TestCase):
    def setUp(self):
        self.c = naada.app.test_client()

    def test_auth_required(self):
        self.assertEqual(self.c.get("/api/v1/search?q=x").status_code, 401)
        self.assertEqual(self.c.get("/api/v1/search?q=x", headers={"X-Naada-Token": "nope"}).status_code, 401)
        self.assertEqual(self.c.get("/api/v1/search?q=x", headers={"Authorization": "Bearer test-token"}).status_code, 200)

    def test_pwa_cookie_still_works(self):
        r = self.c.get("/?k=test-token")
        self.assertEqual(r.status_code, 302)
        self.assertEqual(self.c.get("/api/search?q=x").status_code, 200)

    def test_info(self):
        j = self.c.get("/api/v1/info", headers=H).get_json()
        self.assertEqual(j["api_version"], 1)
        self.assertIn("library_sync", j["features"])

    def test_aliases(self):
        j = self.c.get("/api/v1/search?q=sid", headers=H).get_json()
        self.assertEqual(j["songs"][0]["title"], 'Samajavaragamana (From "AVPL") & more')
        self.assertEqual(j["songs"][0]["id"], "abc123")
        self.assertTrue(j["songs"][0]["url"].endswith("_320.mp4"))
        old = self.c.get("/api/search?q=sid", headers=H).get_json()
        self.assertEqual(old, j)

    def test_stream(self):
        j = self.c.get("/api/v1/stream/abc123", headers=H).get_json()
        self.assertTrue(j["url"].startswith("https://"))
        self.assertNotIn("url", j["song"])
        self.assertEqual(self.c.get("/api/v1/stream/missing", headers=H).status_code, 404)
        self.assertEqual(self.c.get("/api/song/abc123", headers=H).get_json()["id"], "abc123")

    def test_library_flow(self):
        song = {"id": "abc123", "title": "Samajavaragamana", "url": "https://expiring"}
        r = self.c.put("/api/v1/library/likes/abc123", json=song, headers=H)
        self.assertEqual(r.status_code, 200)
        rev = r.get_json()["rev"]
        lib = self.c.get("/api/v1/library", headers=H).get_json()
        self.assertEqual(lib["likes"][0]["id"], "abc123")
        self.assertNotIn("url", lib["likes"][0])
        self.assertTrue(self.c.get(f"/api/v1/library?since_rev={rev}", headers=H).get_json()["unchanged"])

        r = self.c.post("/api/v1/library/playlists", json={"name": "Drive", "songs": [song]}, headers=H)
        self.assertEqual(r.status_code, 201)
        pid = r.get_json()["id"]
        self.c.put(f"/api/v1/library/playlists/{pid}", json={"name": "Long drive", "songs": [song, song]}, headers=H)
        pl = [p for p in self.c.get("/api/v1/library", headers=H).get_json()["playlists"] if p["id"] == pid][0]
        self.assertEqual(pl["name"], "Long drive")
        self.assertEqual(len(pl["songs"]), 2)

        items = [{"song": song, "played_at": 1700000000.5}]
        self.assertEqual(self.c.post("/api/v1/library/history", json={"items": items}, headers=H).get_json()["added"], 1)
        self.assertEqual(self.c.post("/api/v1/library/history", json={"items": items}, headers=H).get_json()["added"], 0)

        self.c.delete(f"/api/v1/library/playlists/{pid}", headers=H)
        self.c.delete("/api/v1/library/likes/abc123", headers=H)
        lib = self.c.get("/api/v1/library", headers=H).get_json()
        self.assertFalse(any(p["id"] == pid for p in lib["playlists"]))
        self.assertFalse(any(s["id"] == "abc123" for s in lib["likes"]))

    def test_folders(self):
        folders = [{"id": "f1", "name": "Chiranjeevi", "playlist_ids": ["p1", "p2"],
                    "songs": [{"song": {"id": "s9", "title": "Abbanee"}, "from": "p3"}, {"song": {"bad": 1}}]}]
        self.assertEqual(self.c.put("/api/v1/library/folders", json={"folders": folders}, headers=H).status_code, 200)
        lib = self.c.get("/api/v1/library", headers=H).get_json()
        f = lib["folders"][0]
        self.assertEqual((f["name"], f["playlist_ids"], len(f["songs"]), f["songs"][0]["from"]), ("Chiranjeevi", ["p1", "p2"], 1, "p3"))
        self.c.put("/api/v1/library/folders", json={"folders": []}, headers=H)
        self.assertEqual(self.c.get("/api/v1/library", headers=H).get_json()["folders"], [])

    def test_new_releases_ids(self):
        old = naada.saavn
        naada.saavn = lambda p, timeout=12: {"data": [{"id": "a1", "title": "One"}, {"albumid": "a2", "title": "Two"}, {"title": "no id"}]}
        try:
            albums = self.c.get("/api/v1/new-releases?lang=telugu", headers=H).get_json()["albums"]
        finally:
            naada.saavn = old
        self.assertEqual([a["id"] for a in albums], ["a1", "a2"])

    def test_import(self):
        body = {"likes": [{"id": "s1", "title": "A"}, {"bad": 1}],
                "playlists": [{"name": "Old", "songs": [{"id": "s2"}]}],
                "history": [{"song": {"id": "s3"}, "played_at": 5}]}
        j = self.c.post("/api/v1/library/import", json=body, headers=H).get_json()
        self.assertEqual((j["likes"], j["playlists"], j["history"]), (1, 1, 1))

    def test_validation(self):
        self.assertEqual(self.c.put("/api/v1/library/likes/x", json={"id": "y"}, headers=H).status_code, 400)
        self.assertEqual(self.c.post("/api/v1/library/playlists", json={}, headers=H).status_code, 400)

    def test_clear_history(self):
        items = [{"song": {"id": "h1"}, "played_at": 42.0}]
        self.c.post("/api/v1/library/history", json={"items": items}, headers=H)
        self.assertEqual(self.c.delete("/api/v1/library/history", headers=H).status_code, 200)
        self.assertEqual(self.c.get("/api/v1/library", headers=H).get_json()["history"], [])

    def test_share(self):
        songs = [{"id": "s1", "title": "Inkem Inkem <script>", "artist": "Sid Sriram, X", "image": "http://c/a.jpg"}]
        self.assertEqual(self.c.post("/api/v1/share", json={"name": "x", "songs": songs}).status_code, 401)
        r = self.c.post("/api/v1/share", json={"name": "Road trip", "songs": songs}, headers=H)
        self.assertEqual(r.status_code, 201)
        sid = r.get_json()["id"]
        self.assertTrue(r.get_json()["url"].endswith("/share/" + sid))
        # Public, no token:
        j = self.c.get(f"/api/v1/share/{sid}").get_json()
        self.assertEqual(j["name"], "Road trip")
        page = self.c.get(f"/share/{sid}")
        self.assertEqual(page.status_code, 200)
        body = page.get_data(as_text=True)
        self.assertIn("open.spotify.com/search/Inkem%20Inkem", body)
        self.assertNotIn("<script>", body)
        self.assertIn("https://c/a.jpg", body)
        self.assertEqual(self.c.get("/share/nope").status_code, 404)
        self.assertEqual(self.c.post("/api/v1/share", json={"songs": []}, headers=H).status_code, 400)


class Accounts(unittest.TestCase):
    def setUp(self):
        self.c = naada.app.test_client()

    def invite(self, **body):
        r = self.c.post("/api/v1/admin/invites", json=body, headers=H)
        self.assertEqual(r.status_code, 201)
        return r.get_json()["code"]

    def join(self, name, pin="2468", code=None):
        r = self.c.post("/api/v1/auth/join", json={"code": code or self.invite(), "name": name, "pin": pin})
        return r

    def test_join_isolation_and_gate(self):
        r = self.join("Lakshmi")
        self.assertEqual(r.status_code, 200, r.get_json())
        tok = r.get_json()["token"]
        me = {"X-Naada-Token": tok}
        # Personal token passes the NAADA_TOKEN gate for catalogue routes too.
        self.assertEqual(self.c.get("/api/v1/search?q=x", headers=me).status_code, 200)
        self.assertFalse(self.c.get("/api/v1/me", headers=me).get_json()["is_admin"])
        # Her like doesn't appear in the owner's library and vice versa.
        self.c.put("/api/v1/library/likes/her1", json={"id": "her1"}, headers=me)
        self.c.put("/api/v1/library/likes/own1", json={"id": "own1"}, headers=H)
        mine = [s["id"] for s in self.c.get("/api/v1/library", headers=me).get_json()["likes"]]
        owners = [s["id"] for s in self.c.get("/api/v1/library", headers=H).get_json()["likes"]]
        self.assertIn("her1", mine); self.assertNotIn("own1", mine)
        self.assertIn("own1", owners); self.assertNotIn("her1", owners)
        # She can't touch the owner's playlist, and isn't admin.
        pid = self.c.post("/api/v1/library/playlists", json={"name": "Owner's"}, headers=H).get_json()["id"]
        self.assertEqual(self.c.put(f"/api/v1/library/playlists/{pid}", json={"name": "x"}, headers=me).status_code, 404)
        self.assertEqual(self.c.post("/api/v1/library/playlists", json={"id": pid, "name": "steal"}, headers=me).status_code, 409)
        self.assertEqual(self.c.get("/api/v1/admin/users", headers=me).status_code, 403)
        # Codes are single use; names are unique.
        code = self.invite()
        self.assertEqual(self.join("Ravi", code=code).status_code, 200)
        self.assertEqual(self.join("Someone", code=code).status_code, 403)
        self.assertEqual(self.join("lakshmi").status_code, 409)

    def test_login_lockout_and_signin_code(self):
        self.assertEqual(self.join("Anand", pin="1357").status_code, 200)
        ok = self.c.post("/api/v1/auth/login", json={"name": "anand", "pin": "1357"})
        self.assertEqual(ok.status_code, 200)
        for _ in range(5):
            self.assertEqual(self.c.post("/api/v1/auth/login", json={"name": "Anand", "pin": "0000"}).status_code, 403)
        self.assertEqual(self.c.post("/api/v1/auth/login", json={"name": "Anand", "pin": "1357"}).status_code, 429)
        # Admin issues a sign-in code; it resets the PIN and unlocks.
        uid = ok.get_json()["user"]["id"]
        code = self.invite(user_id=uid)
        r = self.c.post("/api/v1/auth/join", json={"code": code.lower().replace("-", ""), "pin": "9999"})
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.get_json()["user"]["name"], "Anand")
        self.assertEqual(self.c.post("/api/v1/auth/login", json={"name": "Anand", "pin": "9999"}).status_code, 200)

    def test_admin_remove_and_logout(self):
        r = self.join("Temp")
        tok, uid = r.get_json()["token"], r.get_json()["user"]["id"]
        users = self.c.get("/api/v1/admin/users", headers=H).get_json()["users"]
        self.assertTrue(any(u["id"] == uid for u in users))
        self.assertEqual(self.c.delete(f"/api/v1/admin/users/{uid}", headers=H).status_code, 200)
        self.assertEqual(self.c.get("/api/v1/me", headers={"X-Naada-Token": tok}).status_code, 401)
        self.assertEqual(self.c.delete("/api/v1/admin/users/owner", headers=H).status_code, 400)
        r2 = self.join("Temp")       # name is free again
        self.assertEqual(r2.status_code, 200)
        t2 = {"X-Naada-Token": r2.get_json()["token"]}
        self.c.post("/api/v1/auth/logout", headers=t2)
        self.assertEqual(self.c.get("/api/v1/library", headers=t2).status_code, 401)

    def test_auth_open_without_token_and_pin_rules(self):
        self.assertEqual(self.c.get("/api/v1/info").status_code, 200)
        self.assertIsNone(self.c.get("/api/v1/info").get_json()["user"])
        self.assertEqual(self.c.post("/api/v1/auth/join", json={"code": "ZZZZ-ZZZZ", "name": "x", "pin": "1234"}).status_code, 403)
        self.assertEqual(self.join("ShortPin", pin="12").status_code, 400)
        self.assertEqual(self.c.get("/api/v1/library").status_code, 401)

    def test_owner_sets_pin_then_logs_in(self):
        r = self.c.put("/api/v1/me", json={"name": "Phani", "pin": "4321"}, headers=H)
        self.assertEqual(r.status_code, 200, r.get_json())
        self.assertTrue(r.get_json()["is_admin"])
        lg = self.c.post("/api/v1/auth/login", json={"name": "phani", "pin": "4321"})
        self.assertEqual(lg.status_code, 200)
        self.assertEqual(lg.get_json()["user"]["id"], "owner")
        # Changing it again needs the current PIN.
        self.assertEqual(self.c.put("/api/v1/me", json={"pin": "1111"}, headers=H).status_code, 403)
        self.assertEqual(self.c.put("/api/v1/me", json={"pin": "1111", "current_pin": "4321"}, headers=H).status_code, 200)

    def test_ai_limit(self):
        import api_v1
        tok = {"X-Naada-Token": self.join("AiUser").get_json()["token"]}
        old = api_v1.AI_DAILY_LIMIT
        api_v1.AI_DAILY_LIMIT = 2
        try:
            codes = [self.c.get("/api/v1/ai/anecdote?title=x", headers=tok).get_json().get("error") for _ in range(3)]
            self.assertEqual(codes[-1], "daily_limit")
            self.assertNotEqual(self.c.get("/api/v1/ai/anecdote?title=x", headers=H).get_json().get("error"), "daily_limit")
        finally:
            api_v1.AI_DAILY_LIMIT = old


class Migration(unittest.TestCase):
    def test_v45_database_becomes_owners(self):
        import sqlite3, api_v1
        path = os.path.join(tempfile.mkdtemp(), "old.db")
        conn = sqlite3.connect(path)
        conn.executescript(api_v1.BASE_SCHEMA)
        conn.execute("INSERT INTO likes VALUES('old1','{\"id\":\"old1\"}',1)")
        conn.execute("INSERT INTO playlists VALUES('p1','Old list',1,1)")
        conn.execute("INSERT INTO history(song_id, song_json, played_at) VALUES('old1','{\"id\":\"old1\"}',5)")
        conn.execute("INSERT INTO meta VALUES('rev','7')")
        conn.commit(); conn.close()
        conn = sqlite3.connect(path); conn.row_factory = sqlite3.Row
        api_v1._migrate(conn)
        self.assertEqual(conn.execute("SELECT user_id FROM likes").fetchone()[0], "owner")
        self.assertEqual(conn.execute("SELECT user_id FROM playlists").fetchone()[0], "owner")
        self.assertEqual(conn.execute("SELECT user_id FROM history").fetchone()[0], "owner")
        self.assertEqual(conn.execute("SELECT v FROM meta WHERE k='rev:owner'").fetchone()[0], "7")
        self.assertEqual(conn.execute("SELECT is_admin FROM users WHERE id='owner'").fetchone()[0], 1)
        api_v1._migrate(conn)       # idempotent
        self.assertEqual(conn.execute("PRAGMA user_version").fetchone()[0], 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
