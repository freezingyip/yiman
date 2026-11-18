import pathlib
import sqlite3
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "data/src/main/sqldelight/tachiyomi/data/manga.sq").read_text()
QUERY = SOURCE.split("insertMigrationMangaReturningId:\n", 1)[1].split(";", 1)[0]


class AtomicImportTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("""
            CREATE TABLE manga (
                id INTEGER PRIMARY KEY, source_id INTEGER NOT NULL, remote_url TEXT NOT NULL,
                remote_title TEXT NOT NULL, remote_author TEXT, remote_artist TEXT,
                remote_description TEXT, remote_genre TEXT, remote_status INTEGER NOT NULL,
                remote_cover TEXT, remote_update_strategy INTEGER NOT NULL, remote_memo BLOB NOT NULL,
                user_favorite_at INTEGER, user_notes TEXT NOT NULL, user_reader_flags INTEGER NOT NULL,
                user_chapter_flags INTEGER NOT NULL, state_chapter_last_update INTEGER,
                state_chapter_next_update INTEGER, state_chapter_fetch_interval INTEGER NOT NULL,
                state_cover_last_modified INTEGER, state_initialized INTEGER NOT NULL,
                UNIQUE(source_id, remote_url)
            );
            CREATE TABLE chapter (
                manga_id INTEGER NOT NULL, remote_url TEXT NOT NULL,
                UNIQUE(manga_id, remote_url)
            );
        """)
        self.params = dict(
            sourceId=1, remoteUrl="https://example.invalid/book", remoteTitle="Legacy title",
            remoteAuthor=None, remoteArtist=None, remoteDescription=None, remoteGenre=None,
            remoteStatus=0, remoteCover=None, remoteUpdateStrategy=0, remoteMemo=b"{}",
            userFavoriteAt=1, userNotes="", userReaderFlags=0, userChapterFlags=0,
            stateChapterLastUpdate=None, stateChapterNextUpdate=None, stateChapterFetchInterval=0,
            stateCoverLastModified=None, stateInitialized=1,
        )

    def tearDown(self):
        self.db.close()

    def test_existing_manga_is_never_updated(self):
        first = self.db.execute(QUERY, self.params).fetchone()
        self.db.execute("UPDATE manga SET remote_title = ?, user_notes = ? WHERE id = ?", ("Native", "keep", first[0]))
        second = self.db.execute(QUERY, self.params).fetchone()
        self.assertIsNone(second)
        self.assertEqual(self.db.execute("SELECT remote_title, user_notes FROM manga").fetchone(), ("Native", "keep"))

    def test_failure_while_adding_chapters_can_roll_back_manga(self):
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                manga_id = self.db.execute(QUERY, self.params).fetchone()[0]
                self.db.execute("INSERT INTO chapter VALUES (?, ?)", (manga_id, "chapter"))
                self.db.execute("INSERT INTO chapter VALUES (?, ?)", (manga_id, "chapter"))
        self.assertEqual(self.db.execute("SELECT count(*) FROM manga").fetchone()[0], 0)
        self.assertEqual(self.db.execute("SELECT count(*) FROM chapter").fetchone()[0], 0)


if __name__ == "__main__":
    unittest.main()
