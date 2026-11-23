import urllib.request
import re

raw_base = 'https://raw.githubusercontent.com/Mojang/bedrock-samples/main/resource_pack/'

texture_paths = set()
sound_paths = set()

def fetch_text(path):
    url = raw_base + path
    try:
        with urllib.request.urlopen(url) as response:
            return response.read().decode('utf-8')
    except:
        return None

# Extract texture paths from entity client definitions
print('Extracting texture paths from entities...')
try:
    with urllib.request.urlopen('https://github.com/Mojang/bedrock-samples/tree/main/resource_pack/entity') as response:
        html = response.read().decode('utf-8')
    entity_files = re.findall(r'"name":\s*"([^"]+\.json)"', html)
    for f in entity_files:
        text = fetch_text('entity/' + f)
        if text:
            matches = re.findall(r'"textures?"?\s*:\s*\{[^}]*"[^"]*"\s*:\s*"([^"]+)"', text)
            for m in matches:
                if m.startswith('textures/'):
                    texture_paths.add(m)
            matches2 = re.findall(r'"(?:texture|material)[^"]*"\s*:\s*"([^"]+)"', text)
            for m in matches2:
                if m.startswith('textures/'):
                    texture_paths.add(m)
except Exception as e:
    print('Error entities: ' + str(e))

# Extract from models
print('Extracting from models...')
for f in ['mobs.json', 'blocks.json']:
    text = fetch_text('models/' + f)
    if text:
        matches = re.findall(r'"(?:texture|material)[^"]*"\s*:\s*"([^"]+)"', text)
        for m in matches:
            if m.startswith('textures/'):
                texture_paths.add(m)

# Extract sound paths from sounds.json
print('Extracting sound paths...')
text = fetch_text('sounds.json')
if text:
    matches = re.findall(r'"sound"\s*:\s*"([^"]+)"', text)
    for m in matches:
        if m.startswith('sounds/') or m.startswith('mob.') or m.startswith('block.') or m.startswith('item.') or m.startswith('random.') or m.startswith('note.') or m.startswith('game.') or m.startswith('ambient.') or m.startswith('music.') or m.startswith('ui.') or m.startswith('records.') or m.startswith('subtitles.'):
            sound_paths.add(m)
    matches2 = re.findall(r'"([^"]+\.ogg)"', text)
    for m in matches2:
        if m.startswith('sounds/'):
            sound_paths.add(m)

print('Texture paths: ' + str(len(texture_paths)))
print('Sound paths: ' + str(len(sound_paths)))

# Add to catalog
with open(r'C:\Users\HP\AndroidStudioProjects\PackForge\app\src\main\assets\vanilla_all_keys_complete.txt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# Find where to insert (after header)
insert_idx = 0
for i, line in enumerate(lines):
    if not line.startswith('#') and line.strip():
        insert_idx = i
        break

# Add texture and sound paths
new_entries = []
for tp in sorted(texture_paths):
    new_entries.append(tp + '\n')
for sp in sorted(sound_paths):
    new_entries.append(sp + '\n')

# Insert after header
new_lines = lines[:insert_idx] + new_entries + lines[insert_idx:]

with open(r'C:\Users\HP\AndroidStudioProjects\PackForge\app\src\main\assets\vanilla_all_keys_complete.txt', 'w', encoding='utf-8') as f:
    # Update count
    total = sum(1 for l in new_lines if l.strip() and not l.startswith('#'))
    for i, line in enumerate(new_lines):
        if line.startswith('# Total:'):
            new_lines[i] = '# Total: ' + str(total) + ' identifiers\n'
            break
    f.writelines(new_lines)

print('Added ' + str(len(new_entries)) + ' entries. New total: ' + str(total))