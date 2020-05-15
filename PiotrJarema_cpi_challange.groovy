import com.sap.gateway.ip.core.customdev.util.Message
import java.util.regex.Pattern;
import com.sap.it.rt.adapter.http.api.exception.HttpResponseException
import groovy.json.JsonOutput
import org.apache.camel.*
import org.osgi.framework.*
import java.net.URLDecoder;
import org.apache.camel.Exchange
import org.apache.camel.builder.SimpleBuilder
import groovy.xml.*


Message processData(Message message) {
  
  
	def edmx = this.getClass().getResource('/edmx/services_odata_org_V2_Northwind_Northwind_svc.edmx').text;
      
	def headers = message.getHeaders() ;
	def camelHttpQuery = headers.get("CamelHttpQuery");
	
	
	ProcessUtils pu = new ProcessUtils();
	
	 try {
		EntityProcessor entityProcessor =  new EntityProcessor(edmx);
		QueryBuilder qB = new QueryBuilder(camelHttpQuery,entityProcessor);
	
  
		 def val =  qB.validateQuery(); // check if values correspond with their Odata types according to  https://www.odata.org/documentation/odata-version-2-0/overview/
		 def queryOdata =  qB.buildQuery();
		 message.setProperty("filterCrit", queryOdata);
		 
	} catch(Exception e) {
			// when validation fails exception is thronw which generates a xml response with message description
			pu.prepareException(message,e);
			 
			 
			def body =   createErrorXML(e.getMessage());
			message.setBody(body); 	  	 
	} 
	
	return message;
	
}


class ProcessUtils
{
	
	public String getSimple(Message message) {
		
			Exchange ex = message.exchange
			StringBuilder sb = new StringBuilder()
	
			def evaluateSimple = { simpleExpression ->
					SimpleBuilder.simple(simpleExpression).evaluate(ex, String)
			}
	
			return  evaluateSimple('${camelId}')  
				
	}
	
	
	public prepareException(Message message,Exception e){
		
		
			def headers = message.getHeaders() ;
			def id = headers.get("SAP_MessageProcessingLogID");
	
			def flowId  =  this.getSimple(message);
	
			def result = stopFlow(id, flowId);
 
			//def body =   createErrorXML(e.getMessage());
			
			 
			message.setHeader('Content-Type', 'application/xml');	
		
	}
	
	
	public Object stopFlow(messageId, iFlow){
		
		def bundleContext = FrameworkUtil.getBundle(Class.forName("com.sap.gateway.ip.core.customdev.util.Message")).getBundleContext()
		ServiceReference[] serviceReference = bundleContext.getServiceReferences(CamelContext.class.getName(), "(camel.context.name=${iFlow})");
		CamelContext camelContext = (CamelContext)bundleContext.getService(serviceReference[0])

		def repoEntry = camelContext.getInflightRepository().browse().find{ it.getExchange().getProperty("SAP_MessageProcessingLogID") == messageId}
		if (repoEntry != null){
				 
				def exchange = repoEntry.getExchange() 
				exchange.setProperty(Exchange.ROUTE_STOP, Boolean.TRUE); 
				
		} 
		
			return repoEntry;
	} 
	
	
}

class FilterCriteria 
{
  
	private String paramName;
	private String paramValue;
	private String type;
	
	public String setParamName(String paramName){
	    this.paramName = paramName;
	}
	
	public String getParamName(){
			return paramName;
	}
	
	public String setParamValue(String paramValue){
	    this.paramValue = paramValue;
	}
	
	public String getParamValue(){
			
			
			if(getType() == 'Edm.String'){
				paramValue =  paramValue.replaceAll("%27","%27%27"); //preventing the Quote issue with strings like 59 rue de l'Abbaye
			} else {
				paramValue = URLDecoder.decode(paramValue);	
			}
	        //paramValue = java.net.URLEncoder.encode(paramValue, "UTF-8")
			return paramValue;
	}
	
	public void setType(type){
	        this.type = type;
	}
	
	public String getType(){
	    
	    return type;
	}
	
	public String getCondition(){
	     
	    if (type == 'Edm.DateTime') {
	        return "datetime'"  + getParamValue() + "'";
	    }
	    
	    if (type == 'Edm.String') {
	        return "'"  + getParamValue() + "'";
	    }
	    
	    if (type == 'Edm.Int32') {
	        return  getParamValue() ;
	    }
	    
	   	if (type == 'Edm.Decimal') {
	   	    
	        return   getParamValue() ;
	    }
	    
	    
	}
	
  
}

class QueryBuilder
{   
    
    
    ArrayList<FilterCriteria> filterCriteriaList;
    
    public QueryBuilder(String camelHttpQuery,EntityProcessor entityProcessor) {
      //  println("check111111111111");
		
		def list = [];
	  
        filterCriteriaList  =  new ArrayList<FilterCriteria>();
		def queryParams = "";
		if (camelHttpQuery != null) {
				queryParams = camelHttpQuery.split('&');
		
		
			for(int i = 0; i  <  queryParams.length; i++){
		  
					FilterCriteria fc = new  FilterCriteria();
					
					try {
					fc.setParamName(queryParams[i].split('=')[0]);
					fc.setParamValue(queryParams[i].split('=')[1]); 
					fc.setType(entityProcessor.getDataTypeFromEntity(fc.getParamName()));
					} catch(Exception e) {
						throw new Exception("Wrong variable assign in url");
					}
							  
					addQueryCondition(fc);
					
					list.add(fc.getParamName());
				  
			}  
			
				def secondUniqueList = list.unique();
			
				if ( secondUniqueList.size() !=  filterCriteriaList.size() ) {
					throw new Exception("Duplicate parameters given.");
				}
			}
    } 
    
    
    public addQueryCondition(FilterCriteria fc) {
      
       filterCriteriaList.add(fc);
	   
	   
   }
    
    public String buildQuery() {
           def query = "";
           for(int i = 0; i  <  filterCriteriaList.size(); i++){
                     FilterCriteria fc = filterCriteriaList[i];
                     if( i == 0) {
                         query = fc.getParamName() + " eq " +  fc.getCondition();
                     } else {
                         query = query + " and " + fc.getParamName() + " eq " +  fc.getCondition();
                     }
            }
            
            return query;
        
    }
    
    /*
    
     Preventing faulty backend calls 
    */
    public String validateQuery() {
           
           def error = "";
           for(int i = 0; i  <  filterCriteriaList.size(); i++){
                       FilterCriteria fc = filterCriteriaList[i];
                    
                        def type = fc.getType();
                        if (type == 'Edm.DateTime') {
                	            
								
								//according to  odata spec-  >   https://www.odata.org/documentation/odata-version-2-0/overview/
								
                	            def dateShort =  validate('^\\d\\d\\d\\d-(0?[1-9]|1[0-2])-(0?[1-9]|[12][0-9]|3[01])$',fc.getParamValue());
                	            
                	            //def dateTime =  validate('^\\d\\d\\d\\d-(0?[1-9]|1[0-2])-(0?[1-9]|[12][0-9]|3[01])T(00|[0-9]|1[0-9]|2[0-3]):([0-9]|[0-5][0-9]):([0-9]|[0-5][0-9])$',fc.getParamValue());
                	            
								def dateTime =  validate('^(?:[0-9]{4}-[0-9]{2}-[0-9]{2})?(?:[ T][0-9]{2}:[0-9]{2}:[0-9]{2})?(?:[.,][0-9]{3})?',fc.getParamValue());
								
								
                	            if (dateShort != true && dateTime != true )
                        	    {
                        	             error  = error + ("Validation exception in parameter " +  fc.getParamName()  + " type " + fc.getType()) + " with value " + fc.getParamValue() + "\n"
										 error  = error + "datetime'yyyy-mm-ddThh:mm[:ss[.fffffff]]' NOTE: Spaces are not allowed between datetime and quoted portion. datetime is case-insensitive"
                        	    }
								
								
								if (fc.getParamValue().length() < 10)
								{
										 error  = error + ("Validation exception in parameter " +  fc.getParamName()  + " type " + fc.getType()) + " with value " + fc.getParamValue() + "\n"
										 error  = error + "datetime'yyyy-mm-ddThh:mm[:ss[.fffffff]]' NOTE: Spaces are not allowed between datetime and quoted portion. datetime is case-insensitive"
								}									
                	    }
                	    
                	    if (type == 'Edm.String') {
                	        
                	    }
                	    
                	    if (type == 'Edm.Int32') {
                	         def intValid =  validate('[0-9]+',fc.getParamValue());
                	         if (intValid != true){
                        	            error  = error + ("Validation exception in parameter " +  fc.getParamName()  + " type " + fc.getType()) + " with value " + fc.getParamValue() + "\n"
                        	 }
                	         
                	    }
                	    
                	   	if (type == 'Edm.Decimal') {
                	   	   
                	   	   // this is from  - > https://www.odata.org/documentation/odata-version-2-0/overview/ 
                	   	   def val =  validate("(?i)[0-9]+.[0-9]+M|m",fc.getParamValue());
                	   	   
                	   	   //but this also gives proper results 
                	   	   def val1 =  validate("[0-9]+.[0-9]*",fc.getParamValue());
                	   	   
                	   	   if (val != true && val1 != true)
                	       {
                	             //throw new Exception(fc.getParamValue() + " - validation exception to type " + fc.getType())
                	           error  = error + ("Validation exception in parameter " +  fc.getParamName()  + " type " + fc.getType()) + " with value " + fc.getParamValue() + "\n"
     
                	       }
                	    }
                	    
                	    //could be more and more
                    
            }
            
            
            if(error != ""){
                throw new Exception(error);
            }
            return error;
        
    }
    
    def boolean validate(String regex,String value){
    
            //pattern = Pattern.compile("[0-9]+.[0-9]+M|m");
            
            def pattern =  Pattern.compile(regex);
            def comp = pattern.matcher(value).matches();
            
            return comp;
    }
   
}


class EntityProcessor 
{
    
    private String edmx;
    private String entity;
    
    public EntityProcessor(String edmx) {
            this.edmx = edmx;
    }


	//get only Order entity for best perfomance on further xml processsing
    private String getEntity() { 
        
        if(entity == null) {
            def entityStart = edmx.indexOf("<EntityType Name=\"Order\">"); //should be a parameter - but the challange allows only changing script :)
            def entityEnd = entityStart + edmx.substring(entityStart,edmx.length()).indexOf("</EntityType>") + "</EntityType>".length();  //should be a parameter - but the challange allows only changing script :)
            
            this.entity = edmx.substring(entityStart,entityEnd);
        }
        
        return this.entity;
    }

    public String getDataTypeFromEntity(searchName) throws Exception { 
        
            entity = getEntity();
        
        
            def xmlEdmx = new XmlSlurper().parseText(entity);
            def nodeFound = xmlEdmx.'*'.find { node ->
                 node.name() == 'Property'  && node.@Name == searchName
            }
            
            
            if(nodeFound.@Type == "") {
                throw new Exception(searchName + " does not exists in entity Order")
            }
            
            return nodeFound.@Type;   
    }

}

def  Object createErrorXML(String value){  //try ing to look like odata resp
       
        def builder = new StreamingMarkupBuilder()
        builder.encoding = 'UTF-8'
        String output = builder.bind {
        mkp.xmlDeclaration()
        //namespaces << [meta:'http://schemas.microsoft.com/ado/2007/08/dataservices/metadata'] 
        mkp.declareNamespace('':'http://schemas.microsoft.com/ado/2007/08/dataservices/metadata')
        
         error(id: 1) {
                code  ''
                message 'xml:lang':lang='en-US', value
         } 
        
        }.toString()
        
        
        return output;
}



