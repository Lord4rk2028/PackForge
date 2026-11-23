import urllib.request
import re
import os

raw_base = 'https://raw.githubusercontent.com/Mojang/bedrock-samples/main/resource_pack/'

all_identifiers = set()

def fetch_text(path):
    url = raw_base + path
    try:
        with urllib.request.urlopen(url) as response:
            return response.read().decode('utf-8')
    except Exception as e:
        return None

def extract_identifiers(text):
    if not text:
        return
    # Pattern for minecraft: namespaced identifiers
    mc_ids = re.findall(r'minecraft:[a-z0-9_\./\-]+', text)
    for m in mc_ids:
        all_identifiers.add(m)
    
    # Pattern for animation.* identifiers
    anim_ids = re.findall(r'animation\.[a-z0-9_\.]+', text)
    for m in anim_ids:
        all_identifiers.add(m)
    
    # Pattern for controller.* identifiers
    ctrl_ids = re.findall(r'controller\.[a-z0-9_\.]+', text)
    for m in ctrl_ids:
        all_identifiers.add(m)
    
    # Pattern for particle.* identifiers
    part_ids = re.findall(r'particle\.[a-z0-9_\.]+', text)
    for m in part_ids:
        all_identifiers.add(m)
    
    # Pattern for render.* identifiers
    render_ids = re.findall(r'render\.[a-z0-9_\.]+', text)
    for m in render_ids:
        all_identifiers.add(m)
    
    # Pattern for geometry.* identifiers
    geom_ids = re.findall(r'geometry\.[a-z0-9_\.]+', text)
    for m in geom_ids:
        all_identifiers.add(m)
    
    # Pattern for material.* identifiers
    mat_ids = re.findall(r'material\.[a-z0-9_\.]+', text)
    for m in mat_ids:
        all_identifiers.add(m)
    
    # Pattern for texture.* identifiers
    tex_ids = re.findall(r'texture\.[a-z0-9_\.]+', text)
    for m in tex_ids:
        all_identifiers.add(m)
    
    # Pattern for sound.* identifiers
    sound_ids = re.findall(r'sound\.[a-z0-9_\.]+', text)
    for m in sound_ids:
        all_identifiers.add(m)
    
    # Pattern for generic dotted identifiers (like look_at_target, humanoid.move, etc.)
    generic_ids = re.findall(r'[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){2,}', text)
    for m in generic_ids:
        all_identifiers.add(m)
    
    # Pattern for spawn_egg identifiers
    spawn_ids = re.findall(r'spawn_egg_[a-z0-9_]+', text)
    for m in spawn_ids:
        all_identifiers.add(m)

# List of folders to process
folders = [
    'animations',
    'animation_controllers', 
    'particles',
    'render_controllers',
    'attachables',
    'entity',
    'atmospherics',
    'color_grading',
    'fogs',
    'lighting',
    'models'
]

def process_folder(folder):
    try:
        with urllib.request.urlopen('https://github.com/Mojang/bedrock-samples/tree/main/resource_pack/' + folder) as response:
            html = response.read().decode('utf-8')
        files = re.findall(r'"name":\s*"([^"]+\.json)"', html)
        count = 0
        for f in files:
            # Fix spaces in filenames
            f_fixed = f.replace(' ', '%20')
            text = fetch_text(folder + '/' + f_fixed)
            if text:
                extract_identifiers(text)
                count += 1
        print(f'  {folder}: {count} files processed')
    except Exception as e:
        print(f'  {folder}: Error - {e}')

print('Fetching from Mojang bedrock-samples/main...')
for folder in folders:
    process_folder(folder)

# Texture index files
print('Fetching texture index files...')
for f in ['texture_list.json', 'flipbook_textures.json']:
    text = fetch_text('textures/' + f)
    if text:
        extract_identifiers(text)

# Sounds.json
print('Fetching sounds.json...')
text = fetch_text('sounds.json')
if text:
    extract_identifiers(text)

# UI files
print('Fetching UI files...')
for f in ['_ui_defs.json', '_global_variables.json']:
    text = fetch_text('ui/' + f)
    if text:
        extract_identifiers(text)

# Model files
print('Fetching models...')
for f in ['mobs.json', 'blocks.json']:
    text = fetch_text('models/' + f)
    if text:
        extract_identifiers(text)

# biomes_client.json
print('Fetching biomes_client.json...')
text = fetch_text('biomes_client.json')
if text:
    extract_identifiers(text)

print('\nTotal unique identifiers collected: ' + str(len(all_identifiers)))

# Save to file
output_path = r'C:\Users\HP\AndroidStudioProjects\PackForge\app\src\main\assets\vanilla_all_keys_complete.txt'
with open(output_path, 'w', encoding='utf-8') as f:
    f.write('# COMPLETE VANILLA BEDROCK RAW IDENTIFIERS/KEYS\n')
    f.write('# Source: Mojang bedrock-samples/resource_pack/ (main branch, v1.26.60+)\n')
    f.write('# Extracted via regex from all resource pack JSON files\n')
    f.write('# Includes: animations, animation_controllers, particles, render_controllers, attachables, entity client defs, textures, sounds, UI, models, atmospherics, color_grading, fogs, lighting\n')
    f.write('# Total: ' + str(len(all_identifiers)) + ' identifiers\n\n')
    for id in sorted(all_identifiers):
        f.write(id + '\n')

print('Written to: ' + output_path)