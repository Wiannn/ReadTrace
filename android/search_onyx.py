import urllib.request
import json
url = "https://api.github.com/search/code?q=onyx+screensaver+intent"
req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
try:
    with urllib.request.urlopen(req) as response:
        print(json.loads(response.read().decode())['items'])
except Exception as e:
    print(e)
