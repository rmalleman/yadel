// THIS FILE WAS AUTO-GENERATED. DO NOT ALTER!
import {IReportService} from "./IReportService";
import {IHttpExecute} from "./IHttpExecute";
import ProtoBufBuilder = org.roylance.yadel.ProtoBufBuilder;

export class ReportService implements IReportService {
    httpExecute:IHttpExecute;
    modelFactory:ProtoBufBuilder;

    constructor(httpExecute:IHttpExecute,
                modelFactory:ProtoBufBuilder) {
        this.httpExecute = httpExecute;
        this.modelFactory = modelFactory;
    }
	delete_dag(request: org.roylance.yadel.UIYadelRequest, onSuccess:(response: org.roylance.yadel.UIYadelResponse)=>void, onError:(response:any)=>void) {
            const self = this;
            this.httpExecute.performPost("/rest/report/delete-dag",
                    request.toBase64(),
                    function(result:string) {
                        onSuccess(self.modelFactory.UIYadelResponse.decode64(result));
                    },
                    onError);
        }
	current(request: org.roylance.yadel.UIYadelRequest, onSuccess:(response: org.roylance.yadel.UIYadelResponse)=>void, onError:(response:any)=>void) {
            const self = this;
            this.httpExecute.performPost("/rest/report/current",
                    request.toBase64(),
                    function(result:string) {
                        onSuccess(self.modelFactory.UIYadelResponse.decode64(result));
                    },
                    onError);
        }
	get_dag_status(request: org.roylance.yadel.UIYadelRequest, onSuccess:(response: org.roylance.yadel.UIYadelResponse)=>void, onError:(response:any)=>void) {
            const self = this;
            this.httpExecute.performPost("/rest/report/get-dag-status",
                    request.toBase64(),
                    function(result:string) {
                        onSuccess(self.modelFactory.UIYadelResponse.decode64(result));
                    },
                    onError);
        }
}