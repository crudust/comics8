var gg = {
m: function(g) {
switch (g) {
case 273:
return 0;
}
return 1;
},
b: "42/",
s: function(h) {
var m = /(..)(.)$/.exec(h);
return parseInt(m[2]+m[1], 16).toString(10);
}
};
