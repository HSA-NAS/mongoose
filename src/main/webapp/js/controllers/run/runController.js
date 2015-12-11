define([
	"jquery",
	"handlebars",
	"text!../../../templates/run/tab-header.hbs",
	"text!../../../templates/run/tab-content.hbs"
], function(
	$,
	Handlebars,
    tabHeaderTemplate,
    tabContentTemplate
) {

	var runIdArray;

	function start(array) {
		runIdArray = array;
		var runId = document.getElementById("run.id");
		//  reset run id field
		runId.value = runId.defaultValue;
		sendRequest();
	}

	function sendRequest() {
		var runId = $("#run\\.id").find("input").val();
		$.post("/start", $("#main-form").serialize(), function(response) {
			if(response) {
				if(confirm("Are you sure? " + response) == true) {
					$.post("/stop", {
						"run.id" : runId,
						"type": "remove"
					}, function(response, status) {
						if(status) {
							sendRequest();
						}
					}).fail(function() {
						alert("internal Server Error");
					})
				}
			} else {
				//  reset default config type
				$('#config-type').find('option').prop('selected', function() {
					return this.defaultSelected;
				});
				generateTabsWithRunningScenarios();
			}
		})
	}

	function generateTabsWithRunningScenarios() {
		$.get("/state", function(response) {
			$.each(response, function(index, runId) {
				if(runIdArray.indexOf(runId) < 0) {
					runIdArray.push(runId);
					renderTabByRunId(runId);
					bindEvents(runId);
				}
			});
			//
		});
	}
	function renderTabByRunId(runId) {
		var runIdText = runId;
		var newId = runId.replace(/\./g, "_");
		var tables = [
			{
				"id": newId + "-messages-csv",
				"text": "messages.csv",
				"active": true
			}, {
				"id": newId + "-errors-log",
				"text": "errors.log"
			}, {
				"id": newId + "-perf-avg-csv",
				"text": "perf.avg.csv"
			}, {
				"id": newId + "-perf-sum-csv",
				"text": "perf.sum.csv"
			}
		];
		//
		var charts = [
			{
				"id": "tp-" + newId,
				"text": "Throughput[obj/s]",
				"active": true
			}, {
				"id": "bw-" + newId,
				"text": "Bandwidth[mb/s]"
			}, {
				"id": "lat-" + newId,
				"text": "Latency[us]"
			}, {
				"id": "dur-" + newId,
				"text": "Duration[us]"
			}
		];
		//
		var run = {
			runId: newId,
			runIdText: runIdText,
			tables: tables,
			charts: charts,
			active: true
		};
		var ul = $(".scenario-tabs");
		//  render tab header
		var compiled = Handlebars.compile(tabHeaderTemplate);
		var html = compiled(run);
		ul.append(html);
		//  render tab content
		var div = $(".scenarios-content");
		compiled = Handlebars.compile(tabContentTemplate);
		html = compiled(run);
		div.append(html);
	}

	function bindEvents(runId) {
		//
		$('a[href="#' + runId.replace(/\./g, "_") + '-tab"]').tab('show');
		//
		var element = $("#" + runId.replace(/\./g, "_") + "-tab");
		element.find(".stop").click(function() {
			var currentRunId = $(this).val();
			var currentButton = $(this);
			currentButton.remove();
			$.post("/stop", {
				"run.id" : currentRunId,
				"type" : "stop"
			}, function() {
				//  do nothing
			}).fail(function() {
				alert("Internal Server Error");
			});
		});

		element.find(".kill").click(function() {
			var currentButton = $(this);
			var currentRunId = $(this).attr("value");
			if (confirm("Please note that the test will be shut down if it's running.") === true) {
				currentButton.remove();
				$("#" + currentRunId.replace(/\./g, "_") + "-tab").remove();
				$('a[href="#' + currentRunId.replace(/\./g, "_") + '-tab"]').remove();
				$('a[href="#configuration"]').tab('show');
				$.post("/stop", {
					"run.id" : currentRunId,
					"type" : "remove"
				}, function() {});
			}
		});
	}

	return {
		start: start
	};
});