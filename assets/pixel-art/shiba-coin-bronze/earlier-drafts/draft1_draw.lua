-- Bronze Shiba coin, 64 x 64 pixel art. Drawn from shapes and hand-placed pixels chosen by eye from
-- the app's painted bronze coin; no pixel of that painting is read here.
-- Light: upper left. Run: Aseprite -b --script-param out=<dir> --script draw_coin.lua
local out = app.params["out"] or "."
local W, H = 64, 64
local spr = Sprite(W, H, ColorMode.RGB)
local img = spr.cels[1].image

local function hex(h)
  local r = tonumber(h:sub(1, 2), 16); local g = tonumber(h:sub(3, 4), 16); local b = tonumber(h:sub(5, 6), 16)
  return app.pixelColor.rgba(r, g, b, 255)
end

-- Palette: each colour has one job.
local P = {
  K  = hex("2a1610"), -- outline and features: a warm near-black, not pure black
  D  = hex("4a2414"), -- soft outline round the fur: a dark of the fur itself
  RH = hex("f4c68e"), -- rim specular, where the rim turns to the light
  R1 = hex("d99a62"), -- rim, lit
  R2 = hex("b8743f"), -- rim, mid
  R3 = hex("8a5028"), -- rim, shadow side
  G  = hex("3a1e14"), -- groove under the rim on the lit side (the rim shades it)
  F1 = hex("633a24"), -- recessed field
  F2 = hex("4c2a1b"), -- field in the rim's shadow
  FL = hex("7a4a2e"), -- field's lit inner lip, lower right
  O1 = hex("d48a4c"), -- fur, lit
  O2 = hex("b0682f"), -- fur, mid
  O3 = hex("874a22"), -- fur, shadow
  C1 = hex("f7ead6"), -- cream, lit
  C2 = hex("dcc4a2"), -- cream, shadow
  E  = hex("e8c8a6"), -- inner ear
  T  = hex("c2493a"), -- tongue: the one accent
  M  = hex("4a1a12"), -- mouth interior
  L1 = hex("c9965a"), -- laurel leaf, lit
  L2 = hex("96652f"), -- laurel leaf, underside and stem
  J  = hex("74421f"), -- jacket
  JD = hex("532c16"), -- jacket shadow and collar
  Y  = hex("e2b565"), -- collar tag
  WH = hex("ffffff"), -- eye glint
}

local function px(x, y, c) if x >= 0 and x < W and y >= 0 and y < H then img:drawPixel(x, y, c) end end
local CX, CY = 31.5, 31.5
local function dist(x, y) return math.sqrt((x - CX) ^ 2 + (y - CY) ^ 2) end
-- How much a point on the coin faces the light (upper left): 1 facing it, -1 facing away.
local function lit(x, y)
  local d = dist(x, y); if d == 0 then return 0 end
  return (-(x - CX) - (y - CY)) / (d * 1.41421356)
end
local function inEllipse(x, y, ex, ey, rx, ry) return ((x - ex) / rx) ^ 2 + ((y - ey) / ry) ^ 2 <= 1 end
local function inField(x, y) return dist(x, y) <= 22.3 end

-- 1. The coin: outline, raised rim, groove, recessed field.
for y = 0, H - 1 do for x = 0, W - 1 do
  local d, t = dist(x, y), lit(x, y)
  if d <= 27.2 then
    local c
    if d > 26.1 then c = P.K
    elseif d > 22.9 then
      -- A raised band: lit on the upper left, in shadow on the lower right.
      if t > 0.3 then c = P.R1 elseif t > -0.45 then c = P.R2 else c = P.R3 end
      if d > 24.0 and d <= 25.2 and t > 0.78 then c = P.RH end
      if d <= 23.7 and t < -0.2 then c = P.R3 end
    elseif d > 21.9 then
      -- The step down into the field: dark where the rim shades it, lit on the far lip.
      if t > -0.25 then c = P.G else c = P.FL end
    else
      c = P.F1
      if d > 20.2 and t > 0.3 then c = P.F2 end
    end
    px(x, y, c)
  end
end end

-- 2. The small mark above the head, kept dim so it stays behind the face.
for _, p in ipairs({ {31, 11}, {32, 11}, {30, 12}, {33, 12}, {31, 13}, {32, 13} }) do px(p[1], p[2], P.L2) end
px(31, 12, P.L1); px(32, 12, P.L1)

-- 3. Laurel sprigs, one drawing mirrored. Leaves climb the inside of the rim.
local function leafPair(x, y, flip)
  local function p(dx, dy, c) px(flip and (63 - (x + dx)) or (x + dx), y + dy, c) end
  p(0, 0, P.L2); p(0, 1, P.L2); p(0, 2, P.L2); p(0, 3, P.L2)       -- stem
  -- inner leaf: a short blade rising toward the head, lit along its top
  p(1, -1, P.L1); p(2, -2, P.L1); p(2, -1, P.L2)
  -- outer leaf: against the rim
  p(-1, -1, P.L1); p(-2, -2, P.L1)
end
local stem = { {16, 43}, {15, 39}, {14, 35}, {14, 31}, {14, 27}, {15, 23} }
for _, s in ipairs(stem) do leafPair(s[1], s[2], false); leafPair(s[1], s[2], true) end

-- 4. Body: jacket, cream chest, collar and tag, cut off by the field's edge.
for y = 44, 53 do
  local half = 9 + (y - 44) * 0.75
  for x = math.floor(CX - half), math.ceil(CX + half) do
    if inField(x, y) then
      local c = P.J
      if x > CX + half - 2.5 then c = P.JD end
      local chest = 3.6 - (y - 45) * 0.45
      if y >= 45 and math.abs(x - CX) <= chest then c = (x > CX + chest - 1.2) and P.C2 or P.C1 end
      px(x, y, c)
    end
  end
end
for x = 24, 39 do px(x, 44, P.JD) end
for x = 26, 37 do px(x, 45, P.JD) end
px(31, 46, P.Y); px(32, 46, P.Y); px(31, 47, P.Y); px(32, 47, P.K)

-- 5. Ears: outer triangle, outline, lighter inside. Drawn once and mirrored.
local function tri(x, y, a, b, c)
  local function s(p, q, r) return (p[1] - r[1]) * (q[2] - r[2]) - (q[1] - r[1]) * (p[2] - r[2]) end
  local pt = { x, y }
  local d1, d2, d3 = s(pt, a, b), s(pt, b, c), s(pt, c, a)
  local neg = (d1 < 0) or (d2 < 0) or (d3 < 0); local pos = (d1 > 0) or (d2 > 0) or (d3 > 0)
  return not (neg and pos)
end
local function ear(flip)
  local function X(x) return flip and (63 - x) or x end
  -- A broad, upright ear: wide at the base so it grows out of the head.
  for y = 11, 28 do for x = 15, 32 do
    if tri(x, y, { 21.5, 12 }, { 17, 28 }, { 32, 24 }) then px(X(x), y, P.D) end
  end end
  for y = 11, 28 do for x = 15, 32 do
    if tri(x, y, { 21.6, 13.6 }, { 18.2, 27.4 }, { 30.8, 24.2 }) then px(X(x), y, flip and P.O3 or P.O2) end
  end end
  -- The pale inside, set toward the middle of the coin.
  for y = 11, 28 do for x = 15, 32 do
    if tri(x, y, { 22.4, 17.6 }, { 21.2, 26 }, { 27.4, 23.6 }) then px(X(x), y, P.E) end
  end end
  for y = 18, 25 do px(X(22), y, P.C1) end
  px(X(21), 15, P.O1); px(X(21), 16, P.O1); px(X(20), 18, P.O1); px(X(20), 19, flip and P.O2 or P.O1)
end
ear(false); ear(true)

-- 6. Head: outline, fur with one lit patch and a shadow edge, cream mask.
local HX, HY, RX, RY = 31.5, 33.0, 11.6, 10.6
for y = 20, 46 do for x = 17, 46 do
  if inEllipse(x, y, HX, HY, RX + 1, RY + 1) then px(x, y, P.D) end
end end
for y = 20, 46 do for x = 17, 46 do
  if inEllipse(x, y, HX, HY, RX, RY) then
    local c = P.O2
    if inEllipse(x, y, 28.5, 27.5, 6.2, 3.4) then c = P.O1 end
    if not inEllipse(x, y, 30.3, 31.8, 11.2, 10.2) then c = P.O3 end
    -- Cream: the cheeks rising at the sides, and the muzzle up to the nose.
    local cream = inEllipse(x, y, 31.5, 39.2, 10.6, 6.0) or inEllipse(x, y, 31.5, 36.2, 4.6, 4.2)
    if cream then
      c = P.C1
      if not inEllipse(x, y, 30.4, 37.6, 10.4, 6.2) then c = P.C2 end
    end
    px(x, y, c)
  end
end end
-- Brow marks.
px(27, 28, P.C1); px(28, 28, P.C1); px(28, 29, P.C2)
px(35, 28, P.C1); px(36, 28, P.C1); px(35, 29, P.C2)
-- Eyes, with one catch-light each on the lit side.
for _, ex in ipairs({ 26, 36 }) do
  for y = 31, 33 do px(ex, y, P.K); px(ex + 1, y, P.K) end
  px(ex, 31, P.WH)
end
-- Nose.
for x = 30, 33 do px(x, 35, P.K) end
px(31, 36, P.K); px(32, 36, P.K); px(30, 35, P.O3)
px(31, 37, P.K); px(32, 37, P.K)
-- Smile, open at the middle with the tongue showing.
for _, p in ipairs({ {27, 38}, {28, 39}, {29, 39}, {30, 38}, {33, 38}, {34, 39}, {35, 39}, {36, 38} }) do px(p[1], p[2], P.K) end
for x = 30, 33 do px(x, 39, P.M) end
for x = 30, 33 do px(x, 40, P.T) end
px(31, 41, P.T); px(32, 41, P.T); px(30, 41, P.K); px(33, 41, P.K); px(31, 42, P.K); px(32, 42, P.K)
px(31, 40, hex("dc6a58"))

spr:saveAs(out .. "/shiba_coin_bronze_64.aseprite")
spr:saveCopyAs(out .. "/shiba_coin_bronze_64.png")
print("saved 64x64")
