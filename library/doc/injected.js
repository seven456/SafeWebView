javascript: (function(b) {
    if (typeof(b.Android) != 'undefined') {
        console.log("Android is injected");
    } else {
        console.log("Android initialization begin");
        var a = {
            queue: [],
            callback: function() {
                var d = Array.prototype.slice.call(arguments, 0);
                var c = d.shift();
                var e = d.shift();
                this.queue[c].apply(this, d);
                if (!e) {
                    delete this.queue[c]
                }
            }
        };
        a.delayJsCallBack = a.testLossTime = a.toast = function() {
            var f = Array.prototype.slice.call(arguments, 0);
            if (f.length < 1) {
                throw "Android call error, message:miss method name"
            }
            var e = [];
            for (var h = 1; h < f.length; h++) {
                var c = f[h];
                var j = typeof c;
                e[e.length] = j;
                if (j == "function") {
                    var d = a.queue.length;
                    a.queue[d] = c;
                    f[h] = d
                }
            }
            var k = new Date().getTime();
            var l = f.shift();
            var m = prompt('SafeWebView: ' + JSON.stringify({method: l, types: e, args: f}));
            console.log("invoke " + l + ", time: " + (new Date().getTime() - k));
            var g = JSON.parse(m);
            if (g.code != 200) {
                throw "Android call error, code:" + g.code + ", message:" + g.result
            }
            return g.result
        };
        Object.getOwnPropertyNames(a).forEach(function(d) {
            var c = a[d];
            if (typeof c === "function" && d !== "callback") {
                a[d] = function() {
                    return c.apply(a, [d].concat(Array.prototype.slice.call(arguments, 0)))
                }
            }
        });
        b.Android = a;
        console.log("Android initialization end")
    }
})(window);