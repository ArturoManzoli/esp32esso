// Oster Xpert electronics box: base + 4-screw lid, M3 self-threading screws throughout.
// Export one part at a time, e.g.: openscad -D 'part="base"' -o base.stl electronics-box.scad
// Inner coordinates: origin at the inner floor corner, floor top at z = 0, mains along +Y.

part = "assembly"; // assembly | layout | base | lid

$fn = 40;

wall = 2.4;
floor_t = 2.4;
lid_t = 2.4;
inner_w = 156;
inner_d = 157;
inner_h = 36;
corner_r = 3;

pilot_d = 2;
clear_d = 3.4;
post = 8;
post_pilot_depth = 16;

gland_d = 12.4;
gland_z = 18;
glands_top_x = [46, 122];
gland_right_y = 90;

usb_y = 43;
usb_w = 4.6;
usb_bottom = 16;
usb_cable_d = 4;

ear_len = 11;
ear_w = 14;
ear_t = 4;
ear_y = [12, inner_d - 12];

lip_h = 3;
lip_w = 1.6;
lip_gap = 0.3;

esp_pos = [26, 10];
esp_size = [63, 66];
esp_holes = [[1.5, 4], [61.5, 4], [1.5, 62], [61.5, 62]];
esp_standoff = 5;

ssr2_pos = [29, 90];
ssr2_size = [34, 55];
ssr2_holes = [[3, 2.5], [31, 2.5], [3, 52.5], [31, 52.5]];
ssr2_standoff = 5;

ssr40_pos = [91, 100];
ssr40_size = [62, 45];
ssr40_holes = [[7.5, 22.5], [54.5, 22.5]];
ssr40_standoff = 5;

dim_pos = [68, 95];
dim_size = [16, 50];
dim_rest = 4;
dim_pcb = 1.6;

buck_pos = [4, 100];
buck_size = [21, 45];
buck_holes = [[2.5, 6.7], [18.6, 38.1]];
buck_standoff = 4;

max_pos = [[96.5, 54], [116.5, 54]];
max_size = [15, 32];
max_hole = [7.5, 16];
max_standoff = 4;

// HX711 rotated so the load-cell header (J1) faces up and GND/DT/SCK/VCC faces down.
hx_pos = [[97, 3], [121, 3]];
hx_size = [20, 33];
hx_holes = [[2.4, 4], [17.05, 4]];
hx_tab = [6.9, 14.9];
hx_standoff = 4;

rails = [[10, 59, "5V"], [10, 43, "GND"], [10, 27, "3V3"]];
rail_standoff = 4;
rail_dividers_y = [51, 35];

vent_right_y = [for (i = [0:7]) 106.5 + i * 5];
vent_lid_x = [for (i = [0:10]) 97 + i * 5];

outer = [inner_w + 2 * wall, inner_d + 2 * wall];
post_centers = [[post / 2, post / 2], [inner_w - post / 2, post / 2],
                [post / 2, inner_d - post / 2], [inner_w - post / 2, inner_d - post / 2]];

module rounded_rect(size, r, h) {
  hull() for (x = [r, size[0] - r], y = [r, size[1] - r]) translate([x, y, 0]) cylinder(r = r, h = h);
}

module slot(len, w, h) {
  hull() for (s = [w / 2, len - w / 2]) translate([s, 0, 0]) cylinder(d = w, h = h);
}

module boss(p, h, d = 6.5) {
  translate([p[0], p[1], 0]) cylinder(d = d, h = h);
}

module pilot(p, h) {
  translate([p[0], p[1], -floor_t + 0.8]) cylinder(d = pilot_d, h = h + floor_t);
}

module at(pos) {
  translate([pos[0], pos[1], 0]) children();
}

module dimmer_arm() {
  hook_z = dim_rest + dim_pcb + 0.2;
  translate([0, 3, 0]) rotate([90, 0, 0]) linear_extrude(6)
    polygon([[-1.4, 0], [-0.2, 0], [-0.2, hook_z], [0.6, hook_z], [-0.2, hook_z + 1.4], [-1.4, hook_z + 1.4]]);
}

module dimmer_cradle() {
  for (yc = [15, 35]) {
    translate([0, yc, 0]) dimmer_arm();
    translate([dim_size[0], yc, 0]) mirror([1, 0, 0]) dimmer_arm();
    for (x = [0.4, dim_size[0] - 1.8]) translate([x, yc - 3, 0]) cube([1.4, 6, dim_rest]);
  }
}

module max_mount() {
  boss(max_hole, max_standoff);
  for (x = [-1.4, max_size[0] + 0.3]) translate([x, max_hole[1] - 5, 0]) cube([1.1, 10, max_standoff + 1.6]);
}

// Screws at the JP2 end; the J1 end slides under a fixed tab before the screws go in.
module hx_mount() {
  w = hx_tab[1] - hx_tab[0];
  lip_z = hx_standoff + 1.6 + 0.2;
  for (h = hx_holes) boss(h, hx_standoff, 5.5);
  translate([hx_tab[0], hx_size[1] - 2.5, 0]) cube([w, 2.8, hx_standoff]);
  translate([hx_tab[0], hx_size[1] + 0.3, 0]) cube([w, 1.6, lip_z + 1.2]);
  translate([hx_tab[0], hx_size[1] - 1, lip_z]) cube([w, 2.9, 1.2]);
}

module mounts() {
  at(esp_pos) for (h = esp_holes) boss(h, esp_standoff);
  at(ssr2_pos) for (h = ssr2_holes) boss(h, ssr2_standoff);
  at(ssr40_pos) for (h = ssr40_holes) translate([h[0] - 4, h[1] - 15, 0]) cube([8, 30, ssr40_standoff]);
  at(dim_pos) dimmer_cradle();
  at(buck_pos) for (h = buck_holes) boss(h, buck_standoff);
  for (m = max_pos) at(m) max_mount();
  for (p = hx_pos) at(p) hx_mount();
  for (r = rails) boss(r, rail_standoff, 7);
  for (y = rail_dividers_y) translate([0, y - 0.6, 0]) cube([20, 1.2, 7]);
  for (r = rails) translate([3.6, r[1], 0]) rotate([0, 0, 90]) linear_extrude(0.6)
    text(r[2], size = 3, halign = "center", valign = "center");
}

module mount_pilots() {
  at(esp_pos) for (h = esp_holes) pilot(h, esp_standoff);
  at(ssr2_pos) for (h = ssr2_holes) pilot(h, ssr2_standoff);
  at(ssr40_pos) for (h = ssr40_holes) pilot(h, ssr40_standoff);
  at(buck_pos) for (h = buck_holes) pilot(h, buck_standoff);
  for (m = max_pos) at(m) pilot(max_hole, max_standoff);
  for (p = hx_pos) at(p) for (h = hx_holes) pilot(h, hx_standoff);
  for (r = rails) pilot(r, rail_standoff);
}

module corner_posts() {
  for (c = post_centers) difference() {
    translate([c[0] - post / 2, c[1] - post / 2, 0]) cube([post, post, inner_h]);
    translate([c[0], c[1], inner_h - post_pilot_depth]) cylinder(d = pilot_d, h = post_pilot_depth + 1);
  }
}

module ear() {
  difference() {
    hull() {
      translate([-1, -ear_w / 2, 0]) cube([1, ear_w, ear_t]);
      translate([-ear_len + ear_w / 2 - 1, 0, 0]) cylinder(d = ear_w, h = ear_t);
    }
    translate([-ear_len + ear_w / 2 - 1, 0, -1]) cylinder(d = clear_d, h = ear_t + 2);
  }
}

module ears() {
  for (y = ear_y) {
    translate([-wall + 0.5, y, -floor_t]) ear();
    translate([inner_w + wall - 0.5, y, -floor_t]) mirror([1, 0, 0]) ear();
  }
}

module cutouts() {
  for (x = glands_top_x) translate([x, inner_d - 1, gland_z]) rotate([-90, 0, 0]) cylinder(d = gland_d, h = wall + 2);
  translate([inner_w - 1, gland_right_y, gland_z]) rotate([0, 90, 0]) cylinder(d = gland_d, h = wall + 2);
  translate([-wall - 1, usb_y, 0]) rotate([0, 90, 0]) linear_extrude(wall + 2) hull() {
    translate([-(usb_bottom + usb_w / 2), 0]) circle(d = usb_w);
    translate([-(inner_h + 1), -usb_w / 2]) square([1, usb_w]);
  }
  for (y = vent_right_y) translate([inner_w - 1, y, 4]) rotate([0, -90, 0]) rotate([0, 0, 0])
    translate([0, 0, -(wall + 2)]) slot(20, 2, wall + 2);
}

module base() {
  difference() {
    union() {
      difference() {
        translate([-wall, -wall, -floor_t]) rounded_rect(outer, corner_r, floor_t + inner_h);
        cube([inner_w, inner_d, inner_h + 1]);
      }
      corner_posts();
      mounts();
      ears();
    }
    mount_pilots();
    cutouts();
  }
}

// Print orientation: plate on the bed, lip and USB tongue pointing up.
module lid() {
  tongue_len = inner_h - (usb_bottom + usb_cable_d + 0.3);
  difference() {
    union() {
      translate([-wall, -wall, 0]) rounded_rect(outer, corner_r, lid_t);
      translate([0, 0, lid_t - 0.01]) difference() {
        translate([lip_gap, lip_gap, 0]) cube([inner_w - 2 * lip_gap, inner_d - 2 * lip_gap, lip_h]);
        translate([lip_gap + lip_w, lip_gap + lip_w, -1])
          cube([inner_w - 2 * (lip_gap + lip_w), inner_d - 2 * (lip_gap + lip_w), lip_h + 2]);
        for (c = post_centers) translate([c[0] - post / 2 - 0.6, c[1] - post / 2 - 0.6, -1])
          cube([post + 1.2, post + 1.2, lip_h + 2]);
      }
      translate([-wall + 0.1, usb_y - (usb_w / 2 - 0.2), lid_t - 0.01]) cube([wall - 0.1 + lip_gap + lip_w, usb_w - 0.4, tongue_len]);
    }
    for (c = post_centers) translate([c[0], c[1], -1]) cylinder(d = clear_d, h = lid_t + 2);
    for (x = vent_lid_x) translate([x, 106, -1]) rotate([0, 0, 90]) slot(34, 2, lid_t + 2);
  }
}

// Reference volumes for checking the layout, not printed.
module tblock(n, h = 10) {
  color("RoyalBlue") cube([n * 5.08, 7.5, h]);
}

module pcb(size, holes, c = "SteelBlue") {
  color(c) difference() {
    cube([size[0], size[1], 1.6]);
    for (h = holes) translate([h[0], h[1], -1]) cylinder(d = 3.2, h = 4);
  }
}

module parts() {
  at(esp_pos) translate([0, 0, esp_standoff]) {
    color("#222") cube([esp_size[0], esp_size[1], 1.6]);
    color("SeaGreen") for (y = [0, esp_size[1] - 8]) translate([1.5, y, 1.6]) cube([60, 8, 10]);
    translate([(esp_size[0] - 51) / 2, (esp_size[1] - 28) / 2, 1.6 + 8.5 + 2.5]) {
      color("#333") cube([51, 28, 1.6]);
      color("Silver") translate([51 - 18, 1.5, 1.6]) cube([18, 25, 3.5]);
      color("Silver") translate([-1, 10, 1.6]) cube([6, 8, 3]);
    }
  }
  at(ssr2_pos) translate([0, 0, ssr2_standoff]) {
    color("#222") cube([ssr2_size[0], ssr2_size[1], 1.6]);
    color("#111") for (x = [8, 21]) translate([x, 12, 1.6]) cube([5, 24, 18]);
    for (x = [2, 18]) translate([x, ssr2_size[1] - 8.5, 1.6]) tblock(2);
    for (x = [2, 18]) translate([x, 1, 1.6]) tblock(2);
  }
  at(ssr40_pos) translate([0, 0, ssr40_standoff]) {
    color("DimGray") cube([ssr40_size[0], ssr40_size[1], 22.5]);
    color("Gold") for (x = [7.5, 54.5], y = [7, 38]) translate([x, y, 22.5]) cylinder(d = 7, h = 1);
  }
  at(dim_pos) translate([0, 0, dim_rest]) {
    color("SteelBlue") cube([dim_size[0], dim_size[1], dim_pcb]);
    translate([0.5, dim_size[1] - 9, dim_pcb]) rotate([0, 0, 0]) color("RoyalBlue") cube([15, 8, 10]);
    color("Gold") for (x = [3, 5.54, 8.08, 10.62]) translate([x, 2, dim_pcb]) cube([0.64, 0.64, 11]);
  }
  at(buck_pos) translate([0, 0, buck_standoff]) {
    pcb(buck_size, buck_holes);
    color("Silver") for (y = [4, 34]) translate([10.5, y + 3.5, 1.6]) cylinder(d = 6.3, h = 12);
    color("#333") translate([2, 12, 1.6]) cube([12, 12, 7]);
  }
  for (p = hx_pos) at(p) translate([0, 0, hx_standoff]) {
    pcb(hx_size, hx_holes, "ForestGreen");
    color("#111") translate([6, 12, 1.6]) cube([8, 10, 1.8]);
    color("Gold") for (x = [2.5:2.54:15.2]) translate([x, hx_size[1] - 5.5, 1.6]) cube([0.64, 0.64, 11]);
    color("Gold") for (x = [6.2:2.54:13.9]) translate([x, 3.7, 1.6]) cube([0.64, 0.64, 11]);
  }
  for (m = max_pos) at(m) translate([0, 0, max_standoff]) {
    pcb(max_size, [max_hole]);
    translate([0, max_size[1] - 8, 1.6]) color("LimeGreen") cube([max_size[0], 8, 14]);
    color("Gold") for (x = [2.4:2.54:12.6]) translate([x, -6, 1.6 + 1]) cube([0.64, 6, 0.64]);
  }
  for (r = rails) translate([r[0], r[1], rail_standoff]) color("Goldenrod") cylinder(d = 6.5, h = 8);
  color("#444") {
    for (x = glands_top_x) translate([x, inner_d + wall, gland_z]) rotate([-90, 0, 0]) cylinder(d = 17, h = 18, $fn = 6);
    translate([inner_w + wall, gland_right_y, gland_z]) rotate([0, 90, 0]) cylinder(d = 17, h = 18, $fn = 6);
  }
}

if (part == "base") base();
else if (part == "lid") lid();
else if (part == "layout") { base(); parts(); }
else {
  base();
  parts();
  color("White", 0.25) translate([0, 0, inner_h + lid_t + 25]) mirror([0, 0, 1]) lid();
}
