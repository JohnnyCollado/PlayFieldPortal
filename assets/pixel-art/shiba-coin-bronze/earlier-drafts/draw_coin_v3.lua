-- Bronze Shiba coin, 64 x 64 pixel art, third draft: cleaner and simpler for the size.
-- What changed from draft one: the coin fills the canvas, the head is larger, the head and ears
-- share ONE outline (no lines crossing inside), every shape is mirror-symmetric, the tiny mark above
-- the head and the jacket's small parts are gone, and the laurels are three bold leaves a side.
-- No pixel of the painted coin is read here. Light: upper left.
-- Run: Aseprite -b --script-param out=<dir> --script draw_coin_v3.lua
local out = app.params["out"] or "."
local W, H = 64, 64
local spr = Sprite(W, H, ColorMode.RGB)
local img = spr.cels[1].image

local function hex(h)
  return app.pixelColor.rgba(tonumber(h:sub(1, 2), 16), tonumber(h:sub(3, 4), 16), tonumber(h:sub(5, 6), 16), 255)
end
local P = {
  K  = hex("2a1610"), -- coin outline and the face's features
  D  = hex("4a2414"), -- the head's single outline: a dark of the fur
  RH = hex("f4c68e"), -- rim specular
  R1 = hex("d99a62"), -- rim, lit
  R2 = hex("b8743f"), -- rim, mid
  R3 = hex("8a5028"), -- rim, shadow
  G  = hex("3a1e14"), -- step into the field, shaded by the rim
  FL = hex("7a4a2e"), -- step into the field, far lip catching light
  F1 = hex("5e3622"), -- field
  O1 = hex("d48a4c"), -- fur, lit
  O2 = hex("b0682f"), -- fur, mid
  O3 = hex("874a22"), -- fur, shadow
  C1 = hex("f7ead6"), -- cream, lit
  C2 = hex("dcc4a2"), -- cream, shadow
  E  = hex("ecd0b0"), -- inner ear
  T  = hex("c2493a"), -- tongue: the one accent
  M  = hex("4a1a12"), -- mouth interior
  L1 = hex("cf9c5e"), -- laurel, lit
  L2 = hex("8c5c2a"), -- laurel, underside and stem
  J  = hex("74421f"), -- shoulders
  JD = hex("4f2a15"), -- collar
  Y  = hex("e2b565"), -- collar tag
  WH = hex("ffffff"), -- eye glint
}

local CX, CY = 31.5, 31.5
local function dist(x, y) return math.sqrt((x - CX) ^ 2 + (y - CY) ^ 2) end
local function lit(x, y)
  local d = dist(x, y); if d == 0 then return 0 end
  return (-(x - CX) - (y - CY)) / (d * 1.41421356)
end
local function inEllipse(x, y, ex, ey, rx, ry) return ((x - ex) / rx) ^ 2 + ((y - ey) / ry) ^ 2 <= 1 end
local function tri(x, y, a, b, c)
  local function s(px_, py, q, r) return (px_ - r[1]) * (q[2] - r[2]) - (q[1] - r[1]) * (py - r[2]) end
  local d1, d2, d3 = s(x, y, a, b), s(x, y, b, c), s(x, y, c, a)
  local neg = (d1 < 0) or (d2 < 0) or (d3 < 0); local pos = (d1 > 0) or (d2 > 0) or (d3 > 0)
  return not (neg and pos)
end
local FIELD = 25.6
local function inField(x, y) return dist(x, y) <= FIELD end

-- The picture is built as a grid of colour names first, so shapes can be outlined as one.
local g = {}
for y = 0, H - 1 do g[y] = {} end
local function set(x, y, c) if x >= 0 and x < W and y >= 0 and y < H then g[y][x] = c end end
-- Every shape is placed on both sides at once, so the coin is exactly symmetric.
local function both(x, y, c) set(x, y, c); set(63 - x, y, c) end

-- 1. Coin: outline, three-pixel raised rim, one-pixel step, flat field.
for y = 0, H - 1 do for x = 0, W - 1 do
  local d, t = dist(x, y), lit(x, y)
  if d <= 30.6 then
    local c
    if d > 29.6 then c = "K"
    elseif d > 26.6 then
      if t > 0.3 then c = "R1" elseif t > -0.45 then c = "R2" else c = "R3" end
      if d > 27.6 and d <= 28.6 and t > 0.8 then c = "RH" end
    elseif d > FIELD then
      if t > -0.3 then c = "G" else c = "FL" end
    else c = "F1" end
    set(x, y, c)
  end
end end

-- 2. Laurels: a stem inside the rim and three bold leaves a side.
local stem = { {14, 47}, {13, 46}, {12, 45}, {12, 44}, {11, 43}, {11, 42}, {10, 41}, {10, 40}, {10, 39}, {9, 38},
               {9, 37}, {9, 36}, {9, 35}, {9, 34}, {9, 33}, {10, 32}, {10, 31}, {10, 30}, {11, 29}, {11, 28} }
for _, s in ipairs(stem) do both(s[1], s[2], "L2") end
-- Each leaf is a solid teardrop two pixels thick, lit on top with a dark underside: a cluster, so
-- it reads as a leaf and not as scratches.
for _, l in ipairs({ {12, 45}, {10, 39}, {10, 33} }) do
  local x, y = l[1], l[2]
  both(x + 1, y - 1, "L1"); both(x + 2, y - 2, "L1"); both(x + 3, y - 3, "L1")
  both(x + 1, y - 2, "L1"); both(x + 2, y - 3, "L1")
  both(x + 2, y - 1, "L2"); both(x + 3, y - 2, "L2")
end
both(11, 27, "L1"); both(11, 26, "L1"); both(12, 26, "L1"); both(12, 25, "L1"); both(12, 27, "L2")

-- 3. Shoulders, collar, chest and tag, under the head and cut by the field's edge.
for y = 49, 57 do for x = 0, 31 do
  local dx = CX - x
  if inField(x, y) and dx <= 11 + (y - 49) * 1.3 then
    local c = "J"
    if y >= 52 and dx <= 4.6 - (y - 52) * 0.8 then c = "C1" end
    if (y == 50 or y == 51) and dx <= 8.6 then c = "JD" end
    both(x, y, c)
  end
end end
set(31, 52, "Y"); set(32, 52, "Y"); set(31, 53, "Y"); set(32, 53, "L2")

-- 4. Head and ears as ONE silhouette.
local HX, HY, RX, RY = 31.5, 35.0, 15.5, 13.5
local function inHead(x, y)
  local mx = math.min(x, 63 - x) -- mirror, so both ears are the same triangle
  return inEllipse(x, y, HX, HY, RX, RY) or tri(mx, y, { 20.5, 11.5 }, { 16, 28 }, { 26.5, 23 })
end
local function inEarInner(x, y)
  local mx = math.min(x, 63 - x)
  return tri(mx, y, { 21, 15.5 }, { 19.6, 24 }, { 24.2, 21.8 }) and not inEllipse(x, y, HX, HY, RX - 1, RY - 1)
end
for y = 0, H - 1 do for x = 0, W - 1 do
  if inHead(x, y) then
    local c = "O2"
    if inEllipse(x, y, 27.0, 27.5, 7.2, 3.6) then c = "O1" end
    if inEllipse(x, y, HX, HY, RX, RY) and not inEllipse(x, y, 30.3, 33.8, 15.2, 13.2) then c = "O3" end
    if inEarInner(x, y) then c = "E" end
    -- Cream: cheeks rising at the sides, and the muzzle up to the nose.
    local cream = inEllipse(x, y, HX, HY, RX, RY) and
      (inEllipse(x, y, 31.5, 43.0, 14.2, 7.6) or inEllipse(x, y, 31.5, 39.0, 6.2, 5.6))
    if cream then
      c = "C1"
      if not inEllipse(x, y, 30.3, 41.6, 13.8, 7.6) then c = "C2" end
    end
    set(x, y, c)
  end
end end
-- The outline: every pixel just outside the silhouette, drawn once.
for y = 1, H - 2 do for x = 1, W - 2 do
  if not inHead(x, y) and dist(x, y) <= 29.6 and
    (inHead(x - 1, y) or inHead(x + 1, y) or inHead(x, y - 1) or inHead(x, y + 1)) then
    set(x, y, "D")
  end
end end

-- 5. Face. Features are whole clusters, never single pixels, apart from the two catch-lights.
for _, ex in ipairs({ 24, 37 }) do
  for y = 33, 36 do for x = ex, ex + 2 do set(x, y, "K") end end
  set(ex, 33, "WH")
end
for y = 29, 30 do for x = 24, 27 do both(x, y, "C1") end end          -- brow marks
for x = 29, 34 do set(x, 37, "K") end                                 -- nose
for x = 30, 33 do set(x, 38, "K") end
set(31, 39, "K"); set(32, 39, "K"); set(31, 40, "K"); set(32, 40, "K")
both(26, 41, "K"); both(27, 42, "K"); both(28, 42, "K"); both(29, 42, "K")   -- smile
for x = 30, 33 do set(x, 41, "K"); set(x, 42, "M"); set(x, 43, "T") end      -- open mouth, tongue
both(29, 43, "K"); set(31, 44, "T"); set(32, 44, "T"); both(30, 44, "K"); set(31, 45, "K"); set(32, 45, "K")

for y = 0, H - 1 do for x = 0, W - 1 do
  if g[y][x] then img:drawPixel(x, y, P[g[y][x]]) end
end end
spr:saveAs(out .. "/shiba_coin_bronze_64_v3.aseprite")
spr:saveCopyAs(out .. "/shiba_coin_bronze_64_v3.png")
print("saved v3")
