// This is a version copied/modified from the doo runner:
// https://github.com/bensu/doo/blob/master/library/resources/runners/headless.js

var p = require('webpage').create();
var fs = require('fs');
var sys = require('system');
var os = sys.os;

p.onConsoleMessage = function(msg) {
    console.log(msg);
};

p.onError = function(msg) {
    console.error(msg);
    phantom.exit(1);
};

p.open('file://' + sys.args[1], function() {
    if (status == "fail") {
        console.log("Failed to load: " + sys.args[1]);
        phantom.exit(1);
    }

    p.onCallback = function (x) {
	    var line = x.toString();
	    if (line !== "[NEWLINE]") {
	        console.log(line.replace(/\[NEWLINE\]/g, "\n"));
	    }
    };

    // p.evaluate is sandboxed, can't ship closures across;
    // so, a bit of a hack, better than polling :-P
    var exitCodePrefix = "phantom-exit-code:";
    p.onAlert = function (msg) {
	    var exitCode = msg.replace(exitCodePrefix, "");
	    if (msg != exitCode) {
            phantom.exit(parseInt(exitCode));
        } else {
            console.log("Alert: " + msg)
        }
    };

    p.evaluate(function() {
        ladder.cljs_test_runner.go_BANG_();
    });
});

