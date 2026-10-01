import unittest
from heartbeat_review_server import byte_range

class RangeTests(unittest.TestCase):
    def test_full_file(self):
        self.assertEqual((0,99,False),byte_range(None,100))
        self.assertEqual((0,-1,False),byte_range(None,0))
    def test_browser_seek(self):
        self.assertEqual((10,99,True),byte_range("bytes=10-",100))
        self.assertEqual((10,20,True),byte_range("bytes=10-20",100))
        self.assertEqual((10,99,True),byte_range("bytes=10-200",100))
        self.assertEqual((0,0,True),byte_range("bytes=0-0",1))
        self.assertEqual((99,99,True),byte_range("bytes=99-",100))
    def test_invalid_ranges(self):
        for value in ("bytes=100-", "bytes=20-10", "bytes=-20", "bytes=1-2,5-8",
                      "other", "Bytes=1-2", "bytes=1-2 ", "bytes=-1-2", "bytes=a-b"):
            with self.subTest(header=value), self.assertRaises(ValueError): byte_range(value,100)
        with self.assertRaisesRegex(ValueError, "Unsatisfiable"):
            byte_range("bytes=0-",0)

if __name__ == '__main__': unittest.main()
